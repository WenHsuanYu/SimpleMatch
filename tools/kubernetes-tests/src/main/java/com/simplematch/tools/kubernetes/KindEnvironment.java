package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

/** Owns one private kind client and explicit cluster-creation intent before any node mutation. */
final class KindEnvironment {
  private final KubernetesSettings settings;
  private final VerificationContext run;
  private final DockerIdentity docker;
  private final BoundedCommand commands;
  private final Path privateDirectory;
  private final Path kubeconfig;
  private final String cluster;
  private final String namespace;
  private boolean created;
  private boolean prepared;
  private String clusterUid = "";
  private KubernetesClient client;
  private List<JsonNode> nodes = List.of();

  KindEnvironment(KubernetesSettings settings, VerificationContext run, DockerIdentity docker) {
    this.settings = settings;
    this.run = run;
    this.docker = docker;
    commands = settings.dockerContext().commands(settings.root());
    cluster = settings.mode().cluster(run.runId());
    namespace = KindMode.namespace(run.runId());
    privateDirectory = settings.coordination().resolve("run-" + run.runId());
    kubeconfig = privateDirectory.resolve("kubeconfig");
  }

  void prepare() throws Exception {
    Files.createDirectory(privateDirectory, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
    Files.writeString(privateDirectory.resolve("owner"), run.runId());
    writeOwnership("");
    final String clusters = commands.run(List.of("kind", "get", "clusters"), "",
        Duration.ofSeconds(30)).checked();
    if (clusters.lines().noneMatch(cluster::equals)) {
      created = true;
      writeOwnership(""); // Persist ownership intent before kind can create even a partial node.
      final var yaml = new ObjectMapper(new YAMLFactory());
      final ObjectNode config = (ObjectNode) yaml.readTree(
          settings.root().resolve("deploy/kind/simplematch-live.yaml").toFile());
      config.put("name", cluster);
      for (var node : config.withArray("nodes")) {
        ((ObjectNode) node).putArray("extraMounts").addObject()
            .put("hostPath", privateDirectory.resolve("owner").toString())
            .put("containerPath", "/etc/simplematch-verification-owner").put("readOnly", true);
      }
      final Path configuration = privateDirectory.resolve("kind.json");
      Files.writeString(configuration, new ObjectMapper().writeValueAsString(config));
      commands.run(List.of("kind", "create", "cluster", "--retain", "--name", cluster,
          "--config", configuration.toString(), "--kubeconfig", kubeconfig.toString(),
          "--wait", "300s"), "", Duration.ofMinutes(8)).checked();
    } else {
      if (settings.mode() == KindMode.DISPOSABLE) {
        throw new IllegalStateException("disposable cluster already exists; refusing adoption");
      }
      Files.writeString(kubeconfig, commands.run(List.of("kind", "get", "kubeconfig",
          "--name", cluster), "", Duration.ofSeconds(30)).checked());
    }
    Files.setPosixFilePermissions(kubeconfig, PosixFilePermissions.fromString("rw-------"));
    nodes = OwnedKindCluster.inspect(commands, settings.dockerContext(), cluster);
    client = new KubernetesClient(settings.root(), kubeconfig, "kind-" + cluster, namespace);
    client.execute("", Duration.ofMinutes(6), "wait", "--for=condition=Ready", "nodes",
        "--all", "--timeout=300s").checked();
    KindTopology.validate(client, nodes, cluster);
    KindHealth.requireStable(client, run.evidence(), "initial");
    clusterUid = client.read("get", "namespace", "kube-system", "-o", "json")
        .path("metadata").path("uid").asText();
    if (!created) {
      KubernetesOwnership.requireReusable(settings, nodes, clusterUid);
    }
    prepared = true;
    writeOwnership("");
  }

  void writeOwnership(String namespaceUid) throws Exception {
    new KubernetesOwnership(run.runId(), docker, state(), namespace, namespaceUid,
        settings.mode(), kubeconfig).write(run.evidence());
  }

  KubernetesOwnership.Cluster state() {
    return new KubernetesOwnership.Cluster(cluster, clusterUid, created, prepared,
        nodes.stream().map(node -> node.path("Id").asText()).toList(),
        OwnedKindCluster.volumes(nodes));
  }

  java.util.Optional<OwnedKindCluster> cleanupTarget() {
    return settings.mode().mayDelete(created, prepared) ? java.util.Optional.of(
        new OwnedKindCluster(commands, settings.dockerContext(), state(), kubeconfig, run.runId()))
        : java.util.Optional.empty();
  }

  Path temporaryImageArchive() throws java.io.IOException {
    return Files.createTempFile(privateDirectory, "helper-", ".tar");
  }

  KubernetesClient client() {
    return client;
  }

  String namespace() {
    return namespace;
  }

  String cluster() {
    return cluster;
  }

  Path kubeconfig() {
    return kubeconfig;
  }

  void diagnoseSetup() throws Exception {
    if (!created) {
      Files.writeString(run.evidence().resolve("diagnostics-setup.txt"),
          "Existing dedicated cluster failed validation; no run-created nodes were claimed.\n");
      return;
    }
    nodes = OwnedKindCluster.inspect(commands, settings.dockerContext(), cluster);
    Exception first = null;
    for (var node : nodes) {
      try {
        if (!OwnedKindCluster.hasOwnerMount(node, privateDirectory.resolve("owner"),
            settings.dockerContext())) {
          throw new IllegalStateException("setup diagnostic node has a foreign ownership marker");
        }
        final String id = node.path("Id").asText();
        final var logs = commands.run(settings.dockerContext().command("logs", "--tail", "200",
            id), "", Duration.ofSeconds(15));
        Files.writeString(run.evidence().resolve("setup-node-" + id + ".log"),
            DiagnosticText.redact(logs.output() + logs.error()));
        logs.checked();
      } catch (Exception failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    writeOwnership("");
    if (first != null) {
      throw first;
    }
  }
}
