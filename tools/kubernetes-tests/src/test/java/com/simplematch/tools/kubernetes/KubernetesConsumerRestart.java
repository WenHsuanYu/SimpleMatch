package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Replaces one real Persistence Pod and checks its rehydrated durable Kafka cursor. */
final class KubernetesConsumerRestart {
  private static final String WORKLOAD = "persistence";
  private static final String IMAGE = "simplematch/persistence:local";
  private static final Duration BUDGET = Duration.ofMinutes(3);
  private static final String CURSOR_STATE = """
      SELECT json_build_object(
        'progress', COALESCE((SELECT json_agg(row_to_json(p) ORDER BY p.partition_id)
          FROM (SELECT partition_id, last_processed_offset
            FROM persistence.matching_consumer_progress
            WHERE consumer_name = 'persistence-matching-events') p), '[]'::json),
        'quarantines', (SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
          WHERE status = 'QUARANTINED')
      )::text;
      """;

  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesScenarioTarget target;
  private final ObjectMapper json = new ObjectMapper();
  private final KubernetesPostgres postgres;

  KubernetesConsumerRestart(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    this.context = context;
    target = context.target();
    postgres = new KubernetesPostgres(target);
  }

  void verify() throws Exception {
    final var budget = OperationBudget.start(BUDGET);
    final var beforeDeployment = deployment(budget);
    final var previous = owners(budget);
    assertEquals(2, previous.size(), "Persistence must have two owned replicas");
    assertTrue(previous.stream().allMatch(Owner::ready), "Persistence baseline Pods must be Ready");
    assertEquals(previous.get(0).replicaSetUid(), previous.get(1).replicaSetUid());
    requireReplicaSet(previous.get(0), beforeDeployment, budget);
    context.workloadImages().verifySelected(target.client(), target.run(),
        "consumer-restart-before", WORKLOAD, 2);
    final var baseline = state(budget);
    final var progress = baseline.path("progress");
    requireProgress(progress);
    assertEquals(0, baseline.path("quarantines").asInt(-1));
    final var removed = previous.get(0);
    final var retained = previous.get(1);
    Files.write(target.run().evidence().resolve("persistence-consumer-restart-baseline.json"),
        json.writeValueAsBytes(Map.of("runId", target.run().runId(),
            "deploymentUid", beforeDeployment.path("metadata").path("uid").asText(),
            "removed", removed, "retained", retained, "durableProgress", progress)));

    target.client().execute("", budget.remaining(), "delete", "pod", removed.name(),
        "--wait=false").checked();
    Owner replacement;
    while (true) {
      budget.remaining();
      final var observed = owners(budget);
      assertTrue(observed.stream().anyMatch(owner -> owner.uid().equals(retained.uid())),
          "the unselected Persistence Pod changed during replacement");
      if (observed.size() == 2 && observed.stream().allMatch(Owner::ready)
          && observed.stream().noneMatch(owner -> owner.uid().equals(removed.uid()))) {
        replacement = requireReplacement(removed, retained, observed);
        break;
      }
      Thread.sleep(500);
    }
    final var afterDeployment = deployment(budget);
    assertEquals(beforeDeployment.path("metadata").path("uid"),
        afterDeployment.path("metadata").path("uid"), "Persistence Deployment owner changed");
    context.workloadImages().verifySelected(target.client(), target.run(),
        "consumer-restart-after", WORKLOAD, 2);
    final var after = state(budget);
    requireProgress(after.path("progress"));
    assertEquals(progress, after.path("progress"),
        "durable consumer progress changed during the no-traffic replacement");
    assertEquals(0, after.path("quarantines").asInt(-1));
    final var live = status(replacement.name(), budget);
    requireRestoredOffsets(after.path("progress"), live);
    final var finalOwners = owners(budget);
    assertEquals(replacement, requireReplacement(removed, retained, finalOwners),
        "Persistence owner changed during the live HTTP progress observation");
    Files.write(target.run().evidence().resolve("persistence-consumer-restart.json"),
        json.writeValueAsBytes(Map.of("runId", target.run().runId(),
            "deploymentUid", afterDeployment.path("metadata").path("uid").asText(),
            "removed", removed, "retained", retained, "replacement", replacement,
            "durableProgressBefore", progress, "durableProgressAfter", after.path("progress"),
            "liveConsumerStatus", live)));
  }

  private JsonNode deployment(OperationBudget budget) throws Exception {
    final var value = target.client().readWithin(budget.remaining(Duration.ofSeconds(20)),
        "get", "deployment", WORKLOAD, "-o", "json");
    assertEquals(target.run().runId(), value.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertFalse(value.path("metadata").path("uid").asText().isBlank());
    assertEquals(2, value.path("spec").path("replicas").asInt(-1));
    assertTrue(value.path("status").path("observedGeneration").asLong(-1)
        >= value.path("metadata").path("generation").asLong(Long.MAX_VALUE));
    assertEquals(target.replacements().get(IMAGE),
        value.at("/spec/template/spec/containers/0/image").asText());
    assertEquals(2, value.path("status").path("readyReplicas").asInt(-1));
    return value;
  }

  private void requireReplicaSet(Owner pod, JsonNode deployment, OperationBudget budget)
      throws Exception {
    final var replicaSet = target.client().readWithin(budget.remaining(Duration.ofSeconds(20)),
        "get", "replicaset", pod.replicaSetName(), "-o", "json");
    requireReplicaSetOwner(replicaSet, pod, target.run().runId(),
        deployment.path("metadata").path("uid").asText(), target.replacements().get(IMAGE));
  }

  static void requireReplicaSetOwner(JsonNode replicaSet, Owner pod, String runId,
      String deploymentUid, String image) {
    assertEquals(pod.replicaSetUid(), replicaSet.path("metadata").path("uid").asText());
    assertEquals(runId, replicaSet.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    final var deployment = controller(replicaSet.path("metadata").path("ownerReferences"),
        "Deployment");
    assertEquals("persistence", deployment.path("name").asText());
    assertEquals(deploymentUid, deployment.path("uid").asText(),
        "Persistence ReplicaSet does not belong to this Deployment");
    assertEquals("persistence", replicaSet.at("/spec/selector/matchLabels/app.kubernetes.io~1name")
        .asText());
    assertEquals(image,
        replicaSet.at("/spec/template/spec/containers/0/image").asText());
  }

  private List<Owner> owners(OperationBudget budget) throws Exception {
    final var pods = target.client().readWithin(budget.remaining(Duration.ofSeconds(20)),
        "get", "pods", "-l", "app.kubernetes.io/name=persistence,"
            + "app.kubernetes.io/component=java-service", "-o", "json").required("items");
    final var owners = new ArrayList<Owner>();
    for (var pod : pods) {
      owners.add(requireOwner(pod, target.run().runId(), target.replacements().get(IMAGE)));
    }
    owners.sort(Comparator.comparing(Owner::name));
    return owners;
  }

  static Owner requireOwner(JsonNode pod, String runId, String image) {
    assertFalse(image == null || image.isBlank(), "run has no locked Persistence image");
    assertEquals(runId, pod.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText(), "Persistence Pod belongs to a different run");
    final var replicaSetOwner = controller(pod.path("metadata").path("ownerReferences"),
        "ReplicaSet");
    assertEquals("persistence", pod.at("/spec/containers/0/name").asText());
    assertEquals(image, pod.at("/spec/containers/0/image").asText());
    assertEquals(1, pod.at("/spec/containers").size());
    final var name = pod.path("metadata").path("name").asText();
    final var uid = pod.path("metadata").path("uid").asText();
    final var replicaSet = replicaSetOwner.path("uid").asText();
    final var replicaSetName = replicaSetOwner.path("name").asText();
    assertFalse(name.isBlank() || uid.isBlank() || replicaSet.isBlank()
        || replicaSetName.isBlank());
    final boolean ready = pod.path("metadata").path("deletionTimestamp").isMissingNode()
        && "Running".equals(pod.path("status").path("phase").asText())
        && pod.at("/status/containerStatuses").size() == 1
        && "persistence".equals(pod.at("/status/containerStatuses/0/name").asText())
        && pod.at("/status/containerStatuses/0/ready").asBoolean()
        && java.util.stream.StreamSupport.stream(
            pod.at("/status/conditions").spliterator(), false).anyMatch(condition ->
                "Ready".equals(condition.path("type").asText())
                    && "True".equals(condition.path("status").asText()));
    final var imageId = pod.at("/status/containerStatuses/0/imageID").asText();
    if (ready) {
      assertFalse(imageId.isBlank(), "Ready Persistence Pod has no image identity");
    }
    return new Owner(name, uid, replicaSet, replicaSetName,
        pod.at("/spec/nodeName").asText(), imageId, ready);
  }

  private static JsonNode controller(JsonNode references, String kind) {
    final var controlling = java.util.stream.StreamSupport.stream(references.spliterator(), false)
        .filter(value -> value.path("controller").asBoolean()).toList();
    assertEquals(1, controlling.size(), "resource must have exactly one controller");
    assertEquals(kind, controlling.getFirst().path("kind").asText());
    return controlling.getFirst();
  }

  static Owner requireReplacement(Owner removed, Owner retained, List<Owner> observed) {
    assertEquals(2, observed.size());
    assertTrue(observed.contains(retained), "unselected Persistence owner changed");
    final var replacement = observed.stream().filter(owner -> !owner.uid().equals(retained.uid()))
        .findFirst().orElseThrow();
    assertNotEquals(removed.uid(), replacement.uid(), "Persistence Pod UID was not replaced");
    assertEquals(removed.replicaSetUid(), replacement.replicaSetUid());
    assertTrue(replacement.ready(), "restarted Persistence Pod is not Ready");
    return replacement;
  }

  private JsonNode state(OperationBudget budget) throws Exception {
    return json.readTree(postgres.execute(CURSOR_STATE, Map.of(),
        budget.remaining(Duration.ofSeconds(45))));
  }

  private JsonNode status(String pod, OperationBudget budget) throws Exception {
    try (var forward = target.client().forward("pod/" + pod, 8080,
        target.run().evidence().resolve("persistence-consumer-forward.log"), 0,
        budget.remaining(Duration.ofSeconds(30)));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      final var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + forward.port()
          + "/internal/critical-consumer-status"))
          .timeout(budget.remaining(Duration.ofSeconds(10))).GET().build();
      final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("Persistence consumer status returned HTTP " + response.statusCode());
      }
      return json.readTree(response.body());
    }
  }

  private static void requireProgress(JsonNode positions) {
    assertTrue(positions.isArray() && !positions.isEmpty(),
        "Persistence has no prior durable matching.events traffic");
    for (var row : positions) {
      assertTrue(row.path("partition_id").isIntegralNumber()
          && row.path("partition_id").asInt(-1) >= 0
          && row.path("partition_id").asInt(-1) < 15
          && row.path("last_processed_offset").isIntegralNumber()
          && row.path("last_processed_offset").asLong(-1) >= 0,
          "Persistence has invalid durable partition progress");
    }
  }

  static void requireRestoredOffsets(JsonNode positions, JsonNode status) {
    requireProgress(positions);
    assertFalse(status.path("quarantined").asBoolean(true), "restarted consumer is quarantined");
    final var active = status.path("committedOffsets");
    assertTrue(active.isObject(), "restarted consumer did not expose its progress");
    assertEquals(positions.size(), active.size(), "restarted consumer restored wrong partitions");
    for (var row : positions) {
      final String partition = row.path("partition_id").asText();
      assertTrue(active.has(partition), "restarted consumer omitted partition " + partition);
      assertEquals(row.path("last_processed_offset").asLong() + 1,
          active.path(partition).asLong(-1), "restarted consumer lost its next Kafka offset");
    }
  }

  record Owner(String name, String uid, String replicaSetUid, String replicaSetName,
      String node, String imageId, boolean ready) {}
}
