package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rejects false Pod replacement and missing in-process durable cursor restoration. */
class KubernetesConsumerRestartTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void rejectsWrongRunOrImageAndDoesNotTreatUnreadyPodAsRecovered() throws Exception {
    final var pod = pod();
    final var owner = KubernetesConsumerRestart.requireOwner(pod, "run-1", "image:locked");
    assertEquals("persistence-a", owner.name());
    assertEquals("rs-1", owner.replicaSetUid());
    assertTrue(owner.ready());
    assertThrows(AssertionError.class, () ->
        KubernetesConsumerRestart.requireOwner(pod, "another-run", "image:locked"));
    assertThrows(AssertionError.class, () ->
        KubernetesConsumerRestart.requireOwner(pod, "run-1", "image:other"));
    final var unready = pod.deepCopy();
    ((ObjectNode) unready.at("/status/containerStatuses/0")).put("ready", false);
    assertFalse(KubernetesConsumerRestart.requireOwner(unready, "run-1", "image:locked").ready());
  }

  @Test
  void replicaSetMustBeOwnedByTheCurrentDeployment() throws Exception {
    final var owner = KubernetesConsumerRestart.requireOwner(pod(), "run-1", "image:locked");
    final var replicaSet = json.readTree("""
        {"metadata":{"uid":"rs-1","labels":{"simplematch.io/verification-run":"run-1"},
           "ownerReferences":[{"kind":"Deployment","name":"persistence",
             "uid":"deployment-1","controller":true}]},
         "spec":{"selector":{"matchLabels":{"app.kubernetes.io/name":"persistence"}},
           "template":{"spec":{"containers":[{"image":"image:locked"}]}}}}
        """);
    KubernetesConsumerRestart.requireReplicaSetOwner(replicaSet, owner, "run-1",
        "deployment-1", "image:locked");
    final var wrong = replicaSet.deepCopy();
    ((ObjectNode) wrong.at("/metadata/ownerReferences/0")).put("uid", "foreign-deployment");
    assertThrows(AssertionError.class, () -> KubernetesConsumerRestart.requireReplicaSetOwner(
        wrong, owner, "run-1", "deployment-1", "image:locked"));
  }

  @Test
  void replacementRequiresNewUidAndUnchangedOtherReplica() {
    final var removed = new KubernetesConsumerRestart.Owner("old", "uid-1", "rs-1", "rs-name",
        "node-1", "image-id", true);
    final var retained = new KubernetesConsumerRestart.Owner("sibling", "uid-2", "rs-1", "rs-name",
        "node-2", "image-id", true);
    final var replacement = new KubernetesConsumerRestart.Owner("new", "uid-3", "rs-1", "rs-name",
        "node-1", "image-id", true);
    assertEquals(replacement, KubernetesConsumerRestart.requireReplacement(removed, retained,
        List.of(retained, replacement)));
    assertThrows(AssertionError.class, () -> KubernetesConsumerRestart.requireReplacement(
        removed, retained, List.of(retained, removed)));
    assertThrows(AssertionError.class, () -> KubernetesConsumerRestart.requireReplacement(
        removed, retained, List.of(removed, replacement)));
    final var otherReplicaSet = new KubernetesConsumerRestart.Owner("new", "uid-3", "rs-2",
        "rs-other",
        "node-1", "image-id", true);
    assertThrows(AssertionError.class, () -> KubernetesConsumerRestart.requireReplacement(
        removed, retained, List.of(retained, otherReplicaSet)));
  }

  @Test
  void restoredCursorMustMatchDurableNextOffset() throws Exception {
    final var progress = json.readTree("""
        [{"partition_id":7,"last_processed_offset":11}]
        """);
    final var valid = json.readTree("""
        {"quarantined":false,"committedOffsets":{"7":12}}
        """);
    KubernetesConsumerRestart.requireRestoredOffsets(progress, valid);
    for (String report : new String[] {
        "{\"quarantined\":false,\"committedOffsets\":{}}",
        "{\"quarantined\":false,\"committedOffsets\":{\"7\":11}}",
        "{\"quarantined\":true,\"committedOffsets\":{\"7\":12}}"}) {
      final var invalid = json.readTree(report);
      assertThrows(AssertionError.class, () ->
          KubernetesConsumerRestart.requireRestoredOffsets(progress, invalid));
    }
    assertThrows(AssertionError.class, () -> KubernetesConsumerRestart.requireRestoredOffsets(
        json.readTree("[]"), valid));
  }

  private com.fasterxml.jackson.databind.JsonNode pod() throws Exception {
    return json.readTree("""
        {"metadata":{"name":"persistence-a","uid":"uid-1",
           "labels":{"simplematch.io/verification-run":"run-1"},
           "ownerReferences":[{"kind":"ReplicaSet","uid":"rs-1",
             "name":"rs-name","controller":true}]},
         "spec":{"nodeName":"worker-1","containers":[{"name":"persistence",
           "image":"image:locked"}]},
         "status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],
           "containerStatuses":[{"name":"persistence","ready":true,
             "imageID":"sha256:locked"}]}}
        """);
  }
}
