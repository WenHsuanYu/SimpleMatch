package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Owns only one disposable PostgreSQL, Kafka and Connect environment and its fault targets. */
final class CdcEnvironment implements AutoCloseable {
  final String runId = UUID.randomUUID().toString();
  final int replication;
  final Network network = Network.newNetwork();
  final PostgreSQLContainer postgres;
  final List<KafkaContainer> brokers = new ArrayList<>();
  final GenericContainer<?> worker;
  CdcConnect connect;
  DriverManagerDataSource database;

  CdcEnvironment(int replication) throws IOException {
    this.replication = replication;
    postgres =
        new PostgreSQLContainer("postgres:18.4")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withCommand("postgres", "-c", "wal_level=logical")
            .withLabel("simplematch.run", runId)
            .withLabel("simplematch.test", "s2-cdc")
            .withStartupTimeout(Duration.ofSeconds(90));
    final String voters =
        IntStream.range(0, replication)
            .mapToObj(i -> (i + 1) + "@kafka-" + i + ":9094")
            .collect(Collectors.joining(","));
    for (int index = 0; index < replication; index++) {
      final String hostname = "kafka-" + index;
      final int hostPort = availablePort();
      brokers.add(
          new KafkaContainer("apache/kafka:4.3.1")
              .withNetwork(network)
              .withCreateContainerCmdModifier(
                  command -> {
                    command.withHostName(hostname);
                    command
                        .getHostConfig()
                        .withPortBindings(
                            new PortBinding(
                                Ports.Binding.bindIpAndPort("127.0.0.1", hostPort),
                                ExposedPort.tcp(9092)));
                  })
              .withListener(hostname + ":19092")
              .withEnv("KAFKA_NODE_ID", Integer.toString(index + 1))
              .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
              .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
              .withEnv("KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE", "false")
              .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
              .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", Integer.toString(replication))
              .withEnv(
                  "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", Integer.toString(replication))
              .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", replication == 3 ? "2" : "1")
              .withLabel("simplematch.test", "s2-cdc")
              .withLabel("simplematch.run", runId)
              .withStartupTimeout(Duration.ofMinutes(2)));
    }
    final String internalBootstrap =
        IntStream.range(0, replication)
            .mapToObj(i -> "kafka-" + i + ":19092")
            .collect(Collectors.joining(","));
    final int workerPort = availablePort();
    worker =
        new GenericContainer<>("quay.io/debezium/connect:3.6.0.Final")
            .withNetwork(network)
            .withEnv("BOOTSTRAP_SERVERS", internalBootstrap)
            .withEnv("GROUP_ID", "s2-" + runId)
            .withEnv("CONFIG_STORAGE_TOPIC", "s2-config")
            .withEnv("OFFSET_STORAGE_TOPIC", "s2-offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "s2-status")
            .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", Integer.toString(replication))
            .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", Integer.toString(replication))
            .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", Integer.toString(replication))
            .withEnv("OFFSET_FLUSH_INTERVAL_MS", "60000")
            .withEnv("HEAP_OPTS", "-Xms256m -Xmx512m")
            .withEnv("KEY_CONVERTER", "org.apache.kafka.connect.storage.StringConverter")
            .withEnv("VALUE_CONVERTER", "org.apache.kafka.connect.converters.ByteArrayConverter")
            .withEnv("REST_ADVERTISED_HOST_NAME", "connect")
            .withNetworkAliases("connect")
            .withExposedPorts(8083)
            .withCreateContainerCmdModifier(
                command ->
                    command
                        .getHostConfig()
                        .withPortBindings(
                            new PortBinding(
                                Ports.Binding.bindIpAndPort("127.0.0.1", workerPort),
                                ExposedPort.tcp(8083)))
                        .withRestartPolicy(RestartPolicy.onFailureRestart(0)))
            .waitingFor(Wait.forHttp("/connectors").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2))
            .withLabel("simplematch.test", "s2-cdc")
            .withLabel("simplematch.run", runId);
  }

  /** Selects a private ephemeral port; a competing bind makes container startup fail closed. */
  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  void start() throws Exception {
    postgres.start();
    database =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    for (CdcOwner owner : CdcOwner.values()) {
      FlywayOwners.connect(owner.service, database).migrate();
    }
    Startables.deepStart(brokers.stream()).join();
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("matching.commands", 15, (short) replication)
                      .configs(
                          Map.of(
                              "min.insync.replicas",
                              replication == 3 ? "2" : "1",
                              "unclean.leader.election.enable",
                              "false")),
                  new NewTopic("account.lifecycle", 3, (short) replication)))
          .all()
          .get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    worker.start();
    connect = new CdcConnect("http://" + worker.getHost() + ":" + worker.getMappedPort(8083));
    for (CdcOwner owner : CdcOwner.values()) {
      connect.register(owner, postgres);
    }
  }

  String bootstrap() {
    return brokers.stream()
        .map(KafkaContainer::getBootstrapServers)
        .collect(Collectors.joining(","));
  }

  private void assertBinding(GenericContainer<?> container, int port) {
    final var binding =
        container
            .getDockerClient()
            .inspectContainerCmd(container.getContainerId())
            .exec()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(ExposedPort.tcp(port));
    assertEquals(
        Integer.toString(container.getMappedPort(port)),
        binding[0].getHostPortSpec(),
        "Run-owned port binding changed");
  }

  void stopBrokers() {
    for (var broker : brokers) {
      assertOwned(broker);
      assertBinding(broker, 9092);
      broker.getDockerClient().stopContainerCmd(broker.getContainerId()).withTimeout(10).exec();
    }
  }

  void startBrokers() throws Exception {
    for (var broker : brokers) {
      assertOwned(broker);
      broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
    }
    final Instant deadline = Instant.now().plusSeconds(90);
    boolean ready = false;
    while (Instant.now().isBefore(deadline) && !ready) {
      try (Admin admin =
          Admin.create(
              Map.of(
                  "bootstrap.servers",
                  bootstrap(),
                  "default.api.timeout.ms",
                  "5000",
                  "request.timeout.ms",
                  "5000"))) {
        ready =
            admin.describeCluster().nodes().get(5, java.util.concurrent.TimeUnit.SECONDS).size()
                == replication;
      } catch (java.util.concurrent.ExecutionException
          | java.util.concurrent.TimeoutException unavailable) {
        Thread.sleep(250);
      }
    }
    assertTrue(ready, "Kafka brokers did not recover");
    for (var broker : brokers) {
      assertBinding(broker, 9092);
    }
    connect.awaitAll();
  }

  void crashWorker() throws Exception {
    assertOwned(worker);
    assertBinding(worker, 8083);
    final var docker = worker.getDockerClient();
    final String id = worker.getContainerId();
    final var before = docker.inspectContainerCmd(id).exec();
    docker.killContainerCmd(id).withSignal("KILL").exec();
    final Instant deadline = Instant.now().plusSeconds(90);
    boolean replaced = false;
    while (Instant.now().isBefore(deadline) && !replaced) {
      final var current = docker.inspectContainerCmd(id).exec();
      if (!Boolean.TRUE.equals(current.getState().getRunning())) {
        assertEquals(137L, current.getState().getExitCodeLong());
        docker.startContainerCmd(id).exec();
      } else {
        replaced = !before.getState().getStartedAt().equals(current.getState().getStartedAt());
      }
      if (!replaced) {
        Thread.sleep(100);
      }
    }
    assertTrue(replaced, "Connect did not expose a new process incarnation");
    assertBinding(worker, 8083);
    connect.awaitAll();
  }

  private void assertOwned(GenericContainer<?> container) {
    final var actual =
        container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec();
    assertEquals(runId, actual.getConfig().getLabels().get("simplematch.run"));
  }

  /** Removes every owned container and then the network, preserving all cleanup failures. */
  @Override
  public void close() {
    RuntimeException failure = null;
    final List<AutoCloseable> owned = new ArrayList<>();
    owned.add(worker);
    owned.addAll(brokers);
    owned.add(postgres);
    owned.add(network);
    for (AutoCloseable resource : owned) {
      try {
        resource.close();
      } catch (Exception error) {
        if (failure == null) {
          failure = new IllegalStateException("Owned CDC cleanup failed");
        }
        failure.addSuppressed(error);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
