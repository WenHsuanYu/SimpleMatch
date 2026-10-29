package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates local dependency recovery facts without accessing the deployed environment. */
public final class DependencyRecoveryVerifier {
  /** Creates a verifier for version 2 dependency reports. */
  public DependencyRecoveryVerifier() {}

  /**
   * Verifies a report and derives its local recovery scope from structured facts.
   *
   * @param component expected dependency name
   * @param report path of one JSON report
   * @return local component and fault scope; never an aggregate certification
   * @throws IOException if the report cannot be read as one JSON document
   * @throws IllegalArgumentException if the evidence does not prove the requested recovery
   */
  public String verify(String component, Path report) throws IOException {
    final JsonNode root = JsonReports.read(report);
    ReportFields.require(root.path("schema_version").isInt()
        && root.path("schema_version").intValue() == 2, "Unsupported report schema.");
    ReportFields.require(ReportFields.text(root, "profile").equals("dependency-recovery"),
        "Wrong recovery profile.");
    ReportFields.require(ReportFields.text(root, "component").equals(component),
        "Wrong report component.");
    ReportFields.require(ReportFields.text(root, "status").equals("PASSED"),
        "Recovery status is not PASSED.");
    ReportFields.require(root.path("failure_reason").isMissingNode()
        || root.path("failure_reason").isNull(), "A passed report contains a failure reason.");
    for (String field : List.of("cluster", "context", "namespace", "run_id")) {
      ReportFields.text(root, field);
    }
    final String fault = ReportFields.text(root, "fault_mode");
    ReportFields.require(fault.equals("worker-stop") || fault.equals("pod-restart"),
        "Unknown fault mode.");
    switch (component) {
      case "postgresql" -> verifyPostgres(root, fault);
      case "redis" -> verifyRedis(root, fault);
      case "kafka" -> verifyKafka(root, fault);
      default -> throw new IllegalArgumentException("Unknown dependency component.");
    }
    return "local/" + component + "/" + fault;
  }

  private static void verifyPostgres(JsonNode root, String fault) {
    final JsonNode target = ReportFields.object(root, "target");
    final JsonNode before = ReportFields.object(target, "before");
    final JsonNode after = ReportFields.object(target, "after");
    verifyFault(root, before, after, fault);
    sameIdentity(before, after, List.of("pod", "node", "pvc", "pv"));
    final JsonNode recovery = ReportFields.object(root, "recovery");
    for (String field : List.of("ready", "durable_before", "durable_after", "data_preserved")) {
      ReportFields.flag(recovery, field, true);
    }
    ReportFields.text(recovery, "durable_marker");
    if (fault.equals("worker-stop")) {
      ReportFields.flag(recovery, "owner_worker_replacement_absent", true);
    }
  }

  private static void verifyRedis(JsonNode root, String fault) {
    final JsonNode target = ReportFields.object(root, "target");
    final JsonNode before = ReportFields.object(target, "before");
    final JsonNode after = ReportFields.object(target, "after");
    verifyFault(root, before, after, fault);
    ReportFields.require(
        !ReportFields.text(before, "pod_uid").equals(ReportFields.text(after, "pod_uid")),
        "The Redis Pod identity did not change.");
    ReportFields.text(before, "pod");
    ReportFields.text(after, "pod");
    ReportFields.require(before.path("pvc").isNull() && after.path("pvc").isNull(),
        "Portable Redis must not use a PVC.");
    if (fault.equals("worker-stop")) {
      ReportFields.require(
          !ReportFields.text(before, "node").equals(ReportFields.text(after, "node")),
          "Redis did not move away from the stopped worker.");
    }
    final JsonNode recovery = ReportFields.object(root, "recovery");
    for (String field : List.of("ready", "portable", "disposable_state", "marker_before")) {
      ReportFields.flag(recovery, field, true);
    }
    ReportFields.flag(recovery, "rescheduled_after_worker_loss", fault.equals("worker-stop"));
    ReportFields.flag(recovery, "marker_required_after", false);
  }

  /** Compares broker facts once, then checks the observed quorum and durable marker. */
  private static void verifyKafka(JsonNode root, String fault) {
    final Map<Integer, JsonNode> before = brokerSet(root, "brokers_before");
    final Map<Integer, JsonNode> after = brokerSet(root, "brokers_after");
    ReportFields.require(before.keySet().equals(after.keySet()),
        "Kafka broker identities changed.");
    final String cluster = ReportFields.text(before.values().iterator().next(), "cluster_id");
    for (Map.Entry<Integer, JsonNode> broker : before.entrySet()) {
      sameIdentity(broker.getValue(), after.get(broker.getKey()),
          List.of("pod", "node", "pvc", "pv", "cluster_id"));
      ReportFields.require(ReportFields.text(broker.getValue(), "cluster_id").equals(cluster),
          "Mixed Kafka clusters.");
    }
    final int target = ReportFields.integer(ReportFields.object(root, "target"), "ordinal");
    ReportFields.require(before.containsKey(target), "The Kafka target broker is missing.");
    verifyFault(root, before.get(target), after.get(target), fault);
    final JsonNode quorum = ReportFields.object(root, "quorum");
    ReportFields.flag(quorum, "ready_before", true);
    ReportFields.flag(quorum, "restored", true);
    final int available = ReportFields.integer(quorum, "available_during");
    ReportFields.require(available >= 2 && available <= 3, "Kafka lost its available quorum.");
    ReportFields.require(ReportFields.integer(quorum, "isr_before") == 3
        && ReportFields.integer(quorum, "isr_after") == 3,
        "Kafka ISR was not restored.");
    final JsonNode marker = ReportFields.object(root, "marker");
    ReportFields.text(marker, "topic");
    ReportFields.text(marker, "key");
    ReportFields.flag(marker, "committed_before", true);
    ReportFields.flag(marker, "preserved_after", true);
    ReportFields.require(ReportFields.integer(marker, "record_count_before") >= 1
        && ReportFields.integer(marker, "record_count_after") >= 1,
        "Kafka marker record is missing.");
    final JsonNode recovery = ReportFields.object(root, "recovery");
    for (String field : List.of("ready", "rejoined", "catch_up_complete")) {
      ReportFields.flag(recovery, field, true);
    }
    ReportFields.flag(recovery, "formatted_again", false);
    ReportFields.require(
        ReportFields.text(recovery, "catch_up_probe").equals("log-dirs-offset-lag-zero"),
        "Kafka catch-up lacks an observed offset-lag probe.");
  }

  /** Indexes three distinct brokers without repeating deployment-specific names or slot labels. */
  private static Map<Integer, JsonNode> brokerSet(JsonNode root, String field) {
    final JsonNode brokers = root.path(field);
    ReportFields.require(brokers.isArray() && brokers.size() == 3,
        "Expected three Kafka brokers: " + field);
    final Map<Integer, JsonNode> result = new HashMap<>();
    final Set<String> nodes = new HashSet<>();
    for (JsonNode broker : brokers) {
      final int identifier = ReportFields.integer(broker, "node_id");
      ReportFields.require(result.putIfAbsent(identifier, broker) == null,
          "Duplicate Kafka broker identity.");
      nodes.add(ReportFields.text(broker, "node"));
      ReportFields.text(broker, "pod_uid");
    }
    ReportFields.require(nodes.size() == 3, "Kafka brokers share a worker.");
    return result;
  }

  /** Worker restart can retain a Pod UID; Pod restart must replace that identity. */
  private static void verifyFault(
      JsonNode root, JsonNode before, JsonNode after, String fault) {
    final String beforeUid = ReportFields.text(before, "pod_uid");
    final String afterUid = ReportFields.text(after, "pod_uid");
    if (fault.equals("pod-restart")) {
      ReportFields.require(!beforeUid.equals(afterUid), "The Pod identity did not change.");
      ReportFields.require(root.path("worker_stop").isMissingNode()
          || root.path("worker_stop").isNull(), "Pod restart contains worker-stop evidence.");
      return;
    }
    final JsonNode worker = ReportFields.object(root, "worker_stop");
    final String identity = ReportFields.text(worker, "container_id");
    ReportFields.require(identity.matches("[0-9a-f]{64}"),
        "Worker container identity is not canonical.");
    ReportFields.require(identity.equals(ReportFields.text(worker, "container_id_after")),
        "Worker container changed.");
    ReportFields.require(
        ReportFields.text(worker, "node").equals(ReportFields.text(before, "node")),
        "Wrong worker was stopped.");
    ReportFields.flag(worker, "node_not_ready_observed", true);
    ReportFields.flag(worker, "same_container_restarted", true);
  }

  private static void sameIdentity(JsonNode before, JsonNode after, List<String> fields) {
    for (String field : fields) {
      ReportFields.require(ReportFields.text(before, field).equals(ReportFields.text(after, field)),
          "Identity changed: " + field);
    }
  }
}
