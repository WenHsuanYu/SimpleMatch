package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/** Holds one accepted order, proves actual fail-closed ingress, and recovers delivery after DB loss. */
final class KubernetesCriticalDelivery implements AutoCloseable {
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesMatchingEvents events;
  private final Map<String, String> variables;
  private final KubernetesPostgres postgres;
  private final KubernetesConnectors connectors;
  private final ObjectMapper json = new ObjectMapper();
  private final String postgresUid;
  private final long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
  private JsonNode held;
  private boolean riskPaused;
  private boolean postgresStopped;
  private KubernetesMatchingPause pausedMatching;

  KubernetesCriticalDelivery(KubernetesBusinessEnvironment.PublicOrder context,
      KubernetesMatchingEvents events, Map<String, String> variables) throws Exception {
    this.context = context;
    this.events = events;
    this.variables = Map.copyOf(variables);
    postgres = new KubernetesPostgres(context.target());
    connectors = new KubernetesConnectors(context.target());
    final var database = context.target().client().read("get", "statefulset", "postgres", "-o", "json");
    if (!context.target().run().runId().equals(database.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText()) || database.path("spec").path("replicas").asInt() != 1) {
      throw new IllegalStateException("database fault requires this run's single PostgreSQL owner");
    }
    postgresUid = database.path("metadata").path("uid").asText();
  }

  void prepare() throws Exception {
    riskPaused = true;
    connectors.setState("risk-service-outbox", "PAUSED");
    held = events.positions();
  }

  KubernetesOrderFacts recover(Future<?> client, Path fix) throws Exception {
    final var admission = postgres.await(sql("resting-buy-admission.sql"), variables,
        value -> "ACCEPTED".equals(value.path("state").asText()), remaining());
    final int partition = admission.required("routingPartition").asInt();
    final long start = end(held, "matchingCommands", partition);
    assertEquals(start, end(events.positions(), "matchingCommands", partition),
        "the accepted command must still be held before failure injection");
    final JsonNode observed;
    final var target = context.target();
    try (var management = target.client().forward("pod/" + target.gatewayPod(), 8080,
            fix.resolve("paused-management-forward.log"));
        var matching = KubernetesMatchingPause.stop(context, partition)) {
      pausedMatching = matching;
      requirePausedIngress(fix, management.port());
      Files.writeString(fix.resolve("reject-release"), "reject\n");
      awaitFile(fix.resolve("rejected.json"), client);
      assertEquals("8", json.readTree(fix.resolve("rejected.json").toFile()).path("execType").asText());
      final var rejected = new HashMap<>(variables);
      rejected.put("cl_ord_id", "REJ-" + variables.get("cl_ord_id"));
      final String rejectionSql = "SELECT count(*) FROM risk_service.admission_journal "
          + "WHERE account_id = :'account_id'::uuid AND cl_ord_id = :'cl_ord_id';";
      assertEquals("0", postgres.execute(rejectionSql, rejected, remaining()).strip());
      connectors.setState("risk-service-outbox", "RUNNING", remaining());
      riskPaused = false;
      long published;
      do {
        remaining();
        published = end(events.positions(remaining()), "matchingCommands", partition);
        if (published > start) {
          break;
        }
        Thread.sleep(250);
      } while (true);
      final long eventStart = end(events.positions(remaining()), "matchingEvents", partition);
      postgresStopped = true;
      scalePostgres(0, remaining());
      target.client().execute("", remaining(), "delete", "pod", "postgres-0", "--ignore-not-found",
          "--grace-period=1", "--wait=false").checked();
      do {
        remaining();
        if (postgresPods() == 0) {
          break;
        }
        Thread.sleep(250);
      } while (true);
      matching.close();
      observed = events.observeAccepted(admission, eventStart, "outage");
      assertEquals(0, postgresPods(), "Matching must publish while PostgreSQL has no Pod");
      final long input = observed.required("context").required("sourceInputOffset").asLong();
      assertTrue(input >= start && input < published);
      restorePostgres();
    }
    final var durableVariables = new HashMap<>(variables);
    durableVariables.put("order_id", admission.required("orderId").asText());
    durableVariables.put("event_id", observed.required("eventId").asText());
    final var durable = postgres.await(sql("resting-buy-durable-state.sql"), durableVariables,
        value -> List.of("persistence", "account", "quickfix").stream()
            .allMatch(owner -> value.path("events").path(owner + "Count").asInt() == 1), remaining());
    final String deliverySql;
    try (var resource = java.util.Objects.requireNonNull(
        getClass().getResourceAsStream("/resting-buy/gateway-delivery.sql"))) {
      deliverySql = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    final var delivery = postgres.await(deliverySql, variables,
        value -> "SENT".equals(value.path("status").asText()), remaining());
    assertEquals(observed.path("eventId"), delivery.path("eventId"));
    Files.writeString(fix.resolve("complete.next"), delivery.required("execId").asText());
    Files.move(fix.resolve("complete.next"), fix.resolve("complete"), StandardCopyOption.ATOMIC_MOVE);
    awaitFile(fix.resolve("completed.json"), client);
    context.admission().open(context.target(), "outage-recovered");
    final var finalAdmission = json.readTree(postgres.execute(sql("resting-buy-admission.sql"), variables));
    assertEquals(admission.path("commandId"), finalAdmission.path("commandId"));
    final var facts = new KubernetesOrderFacts(finalAdmission, delivery, durable,
        json.readTree(fix.resolve("completed.json").toFile()));
    Files.writeString(fix.resolve("facts.json"), json.writeValueAsString(facts));
    return facts;
  }

  private void requirePausedIngress(Path fix, int port) throws Exception {
    do {
      final var status = context.admission().status(port, remaining());
      if ("NEW_ORDERS_PAUSED".equals(status.path("gateState").asText())) {
        Files.writeString(fix.resolve("gateway-paused.json"), status.toPrettyString());
        return;
      }
      Thread.sleep(250);
    } while (true);
  }

  private int postgresPods() throws Exception {
    return context.target().client().readWithin(remaining(), "get", "pods", "-l",
        "app.kubernetes.io/name=postgres," + OwnedNamespace.OWNER_LABEL + "="
            + context.target().run().runId(), "-o", "json").path("items").size();
  }

  private void scalePostgres(int replicas) throws Exception {
    scalePostgres(replicas, Duration.ofSeconds(45));
  }

  private void scalePostgres(int replicas, Duration duration) throws Exception {
    final var budget = OperationBudget.start(duration);
    final var target = context.target();
    final var current = target.client().readWithin(budget.remaining(),
        "get", "statefulset", "postgres", "-o", "json");
    if (!postgresUid.equals(current.path("metadata").path("uid").asText())) {
      throw new IllegalStateException("PostgreSQL workload identity changed; refusing scale");
    }
    target.client().execute("", budget.remaining(), "scale", "statefulset/postgres",
        "--replicas=" + replicas).checked();
  }

  private void restorePostgres() throws Exception {
    if (postgresStopped) {
      scalePostgres(1);
      context.target().client().waitFor("jsonpath={.status.readyReplicas}=1", "statefulset/postgres");
      postgresStopped = false;
    }
  }

  private void awaitFile(Path file, Future<?> client) throws Exception {
    while (!Files.isRegularFile(file)) {
      remaining();
      if (client.isDone()) {
        client.get();
        throw new IllegalStateException("FIX client stopped before " + file.getFileName());
      }
      Thread.sleep(100);
    }
  }

  private static long end(JsonNode positions, String topic, int partition) {
    return java.util.stream.StreamSupport.stream(
        positions.required(topic).required("partitions").spliterator(), false)
        .filter(value -> value.required("partition").asInt() == partition)
        .mapToLong(value -> value.required("offset").asLong()).findFirst().orElseThrow();
  }

  private String sql(String name) throws Exception {
    return Files.readString(context.settings().root()
        .resolve("scripts/end-to-end/critical-consumers/sql").resolve(name));
  }

  private Duration remaining() throws TimeoutException {
    final long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new TimeoutException("critical delivery recovery exceeded three minutes");
    }
    final Duration available = Duration.ofNanos(nanos);
    return pausedMatching == null ? available : pausedMatching.limit(available);
  }

  /** Restores this run's database and connector even when the scenario was interrupted. */
  @Override
  public void close() throws Exception {
    final boolean interrupted = Thread.interrupted();
    try {
      KubernetesTeardown.clean(this::restorePostgres, () -> {
        if (riskPaused) {
          connectors.setState("risk-service-outbox", "RUNNING");
          riskPaused = false;
        }
      });
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
