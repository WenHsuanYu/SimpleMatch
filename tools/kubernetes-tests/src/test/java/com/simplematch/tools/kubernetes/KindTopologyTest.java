package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KindTopologyTest {
  @TempDir Path temporary;

  @Test
  void bindsEachReadyKubernetesRoleToItsObservedDockerNode() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      api.put("/api/v1/nodes", nodes(false, "fixture"));
      KindTopology.validate(api.client(), docker(api), "fixture");
      api.put("/api/v1/nodes", nodes(false, "other-cluster"));
      assertThrows(IllegalStateException.class,
          () -> KindTopology.validate(api.client(), docker(api), "fixture"));
    }
  }

  @Test
  void fourReadyObjectsAndThreeSlotValuesDoNotReplaceTheControlPlaneContract() throws Exception {
    try (var api = new KubernetesApiFixture(temporary)) {
      api.put("/api/v1/nodes", nodes(true, "fixture"));
      assertThrows(IllegalStateException.class,
          () -> KindTopology.validate(api.client(), docker(api), "fixture"));
    }
  }

  private Map<String, Object> nodes(boolean fourthWorker, String provider) {
    final var nodes = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 4; i++) {
      final String name = "observed-" + i;
      final Map<String, String> labels = i == 0 && !fourthWorker
          ? Map.of("simplematch.io/node-pool", "control-plane",
              "node-role.kubernetes.io/control-plane", "")
          : Map.of("simplematch.io/node-pool", "local-resilience",
              "simplematch.io/worker-slot", Integer.toString(Math.max(0, i - 1)));
      nodes.add(Map.of("metadata", Map.of("name", name, "labels", labels),
          "spec", Map.of("providerID", "kind://docker/" + provider + "/" + name),
          "status", Map.of("conditions", List.of(Map.of("type", "Ready", "status", "True")))));
    }
    return Map.of("apiVersion", "v1", "kind", "NodeList", "items", nodes);
  }

  private List<com.fasterxml.jackson.databind.JsonNode> docker(KubernetesApiFixture api) {
    final var result = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (int i = 0; i < 4; i++) {
      result.add(api.json.valueToTree(Map.of("Id", Integer.toString(i).repeat(64),
          "Name", "/observed-" + i, "Config", Map.of("Hostname", "observed-" + i,
              "Labels", Map.of("io.x-k8s.kind.cluster", "fixture",
                  "io.x-k8s.kind.role", i == 0 ? "control-plane" : "worker")))));
    }
    return List.copyOf(result);
  }
}
