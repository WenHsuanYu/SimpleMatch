package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fails closed when a Query replica is not Ready or its run image is replaced. */
class KubernetesQueryHealthTest {
  @Test
  void requiresOneReadyNonTerminatingContainerAndLockedImage() throws Exception {
    final var pod = new ObjectMapper().readTree("""
        {"metadata":{"name":"query-service-a","uid":"pod-a"},
         "spec":{"nodeName":"worker-1","containers":[{"name":"query-service",
           "image":"simplematch/query-service:test"}]},
         "status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],
           "containerStatuses":[{"name":"query-service","ready":true,
             "restartCount":0,"imageID":"sha256:image-id"}]}}
        """);
    assertEquals("pod-a", KubernetesQueryHealth.requireOwner(
        pod, "simplematch/query-service:test").uid());
    final var stale = pod.deepCopy();
    ((ObjectNode) stale.at("/spec/containers/0")).put("image", "simplematch/query-service:old");
    assertThrows(AssertionError.class, () -> KubernetesQueryHealth.requireOwner(
        stale, "simplematch/query-service:test"));
    final var notReady = pod.deepCopy();
    ((ObjectNode) notReady.at("/status/containerStatuses/0")).put("ready", false);
    assertThrows(AssertionError.class, () -> KubernetesQueryHealth.requireOwner(
        notReady, "simplematch/query-service:test"));
    final var deleting = pod.deepCopy();
    ((ObjectNode) deleting.path("metadata")).put("deletionTimestamp", "2026-11-05T00:00:00Z");
    assertThrows(AssertionError.class, () -> KubernetesQueryHealth.requireOwner(
        deleting, "simplematch/query-service:test"));
  }

  @Test
  void rejectsChangedPodIdentityAndRestartsAcrossOutageStages() {
    final var original = new KubernetesQueryHealth.Owner(
        "query-0", "uid-1", "worker-1", "sha256:original", 0);
    final var baseline = List.of(original);
    KubernetesQueryHealth.requireStableOwners(baseline, baseline);
    for (var changed : List.of(
        new KubernetesQueryHealth.Owner("query-0", "uid-2", "worker-1", "sha256:original", 0),
        new KubernetesQueryHealth.Owner("query-0", "uid-1", "worker-2", "sha256:original", 0),
        new KubernetesQueryHealth.Owner("query-0", "uid-1", "worker-1", "sha256:changed", 0),
        new KubernetesQueryHealth.Owner("query-0", "uid-1", "worker-1", "sha256:original", 1))) {
      assertThrows(AssertionError.class,
          () -> KubernetesQueryHealth.requireStableOwners(baseline, List.of(changed)));
    }
  }

  @Test
  void rejectsReadyRedisAndFailedHttpObservations() throws Exception {
    final var json = new ObjectMapper();
    final var redis = json.readTree("""
        {"metadata":{"labels":{"simplematch.io/verification-run":"run-1"}},
         "spec":{"replicas":0},"status":{"readyReplicas":0}}
        """);
    final var absent = json.readTree("{\"items\":[]}");
    KubernetesQueryHealth.requireRedisStopped(redis, absent.path("items"), "run-1");
    final var readyRedis = json.readTree("""
        {"status":{"conditions":[{"type":"Ready","status":"True"}]}}
        """);
    final var pods = json.createArrayNode().add(readyRedis);
    assertThrows(AssertionError.class,
        () -> KubernetesQueryHealth.requireRedisStopped(redis, pods, "run-1"));

    final var up = json.readTree("{\"status\":\"UP\"}");
    KubernetesQueryHealth.requireHealth(200, up, "readiness");
    assertThrows(AssertionError.class, () ->
        KubernetesQueryHealth.requireHealth(500, up, "readiness"));
    assertThrows(AssertionError.class, () ->
        KubernetesQueryHealth.requireHealth(200, json.readTree("{\"status\":\"DOWN\"}"),
            "readiness"));
    final var freshness = json.readTree("{\"partitions\":[]}");
    KubernetesQueryHealth.requireOperations(200, "1", freshness);
    assertThrows(AssertionError.class, () ->
        KubernetesQueryHealth.requireOperations(503, "1", freshness));
    assertThrows(AssertionError.class, () ->
        KubernetesQueryHealth.requireOperations(200, "2", freshness));
  }
}
