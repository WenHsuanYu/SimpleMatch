package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies actual Pod image IDs against the content proved on the original node containers. */
final class KubernetesWorkloadImages {
  private final BoundedCommand commands;
  private final DockerContext context;
  private final Set<String> nodeIds;
  private final Map<String, String> configs;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesWorkloadImages(BoundedCommand commands, DockerContext context, List<String> nodeIds,
      Map<String, String> configs) {
    this.commands = commands;
    this.context = context;
    this.nodeIds = Set.copyOf(nodeIds);
    this.configs = Map.copyOf(configs);
  }

  void verify(KubernetesClient client, VerificationContext run, String phase) throws Exception {
    final var facts = new ArrayList<Map<String, String>>();
    final var nodes = new LinkedHashMap<String, String>();
    final var resolved = new LinkedHashMap<String, String>();
    for (var pod : client.read("get", "pods", "-l", OwnedNamespace.OWNER_LABEL + "=" + run.runId(),
        "-o", "json").path("items")) {
      if (!pod.path("metadata").path("name").asText().startsWith("image-probe-")) {
        verifyPod(pod, nodes, resolved, facts);
      }
    }
    Files.write(run.evidence().resolve("workload-image-identities-" + phase + ".json"),
        json.writeValueAsBytes(facts));
    if (facts.isEmpty()) {
      throw new IllegalStateException("no deployed workload image identity was observed");
    }
  }

  private String nodeIdentity(String name, Map<String, String> nodes) throws Exception {
    if (!nodes.containsKey(name)) {
      final String id = json.readTree(commands.run(context.command("inspect", name), "",
          Duration.ofSeconds(15)).checked()).get(0).path("Id").asText();
      if (!nodeIds.contains(id)) {
        throw new IllegalStateException("workload node differs from the original Docker identity");
      }
      nodes.put(name, id);
    }
    return nodes.get(name);
  }

  private void verifyPod(JsonNode pod, Map<String, String> nodes, Map<String, String> resolved,
      List<Map<String, String>> facts) throws Exception {
    if (pod.path("metadata").path("uid").asText().isBlank()) {
      throw new IllegalStateException("workload Pod has no identity");
    }
    final boolean failed = "Failed".equals(pod.path("status").path("phase").asText());
    if (failed && !hasExecutionStatus(pod)) {
      return; // No container executed; the historical attempt remains in Job diagnostics.
    }
    final String node = nodeIdentity(pod.path("spec").path("nodeName").asText(), nodes);
    verifyStatuses(pod, node, requestedImages(pod), resolved, facts);
    if (!failed) {
      requireCompleteStatuses(pod);
    }
  }

  private static boolean hasExecutionStatus(JsonNode pod) {
    return !pod.path("status").path("containerStatuses").isEmpty()
        || !pod.path("status").path("initContainerStatuses").isEmpty();
  }

  private static void requireCompleteStatuses(JsonNode pod) {
    for (String field : List.of("containers", "initContainers")) {
      final String statusField = "containers".equals(field)
          ? "containerStatuses" : "initContainerStatuses";
      if (pod.path("status").path(statusField).size() != pod.path("spec").path(field).size()) {
        throw new IllegalStateException("workload container identity is incomplete");
      }
    }
  }

  private void verifyStatuses(JsonNode pod, String node, Map<String, String> requested,
      Map<String, String> resolved, List<Map<String, String>> facts) throws Exception {
    for (String field : List.of("containerStatuses", "initContainerStatuses")) {
      for (var status : pod.path("status").path(field)) {
        if (neverStarted(status)) {
          continue;
        }
        final String reference = requested.get(status.path("name").asText());
        final String actual = status.path("imageID").asText();
        final String key = node + "/" + actual;
        if (!resolved.containsKey(key)) {
          resolved.put(key, configIdentity(node, actual));
        }
        if (!configs.containsKey(reference) || !configs.get(reference).equals(resolved.get(key))) {
          throw new IllegalStateException("actual workload image differs from selected content: "
              + reference);
        }
        facts.add(Map.of("pod_uid", pod.path("metadata").path("uid").asText(),
            "container", status.path("name").asText(), "node_id", node,
            "requested_image", reference, "actual_image_id", actual,
            "config_id", resolved.get(key)));
      }
    }
  }

  /** Resolves the reported immutable digest without depending on containerd's import alias. */
  private String configIdentity(String node, String actual) throws Exception {
    final String digest = actual.substring(actual.lastIndexOf('@') + 1);
    JsonNode manifest = content(node, digest);
    if (manifest.path("manifests").isArray()) {
      final var selected = new ArrayList<String>();
      for (var descriptor : manifest.path("manifests")) {
        if ("linux".equals(descriptor.path("platform").path("os").asText())
            && "amd64".equals(descriptor.path("platform").path("architecture").asText())) {
          selected.add(descriptor.path("digest").asText());
        }
      }
      if (selected.size() != 1) {
        throw new IllegalStateException("workload index must identify one linux/amd64 manifest");
      }
      manifest = content(node, selected.get(0));
    }
    final String config = manifest.path("config").path("digest").asText();
    if (!config.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException("workload manifest has no valid config identity");
    }
    return config;
  }

  /** Reads and verifies exact content bytes on the original, already validated Docker node. */
  private JsonNode content(String node, String digest) throws Exception {
    if (!digest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException("workload has no valid immutable content identity");
    }
    final String bytes = commands.run(context.command("exec", node, "ctr", "--namespace",
        "k8s.io", "content", "get", digest), "", Duration.ofSeconds(15)).checked();
    final String observed = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes.getBytes(StandardCharsets.UTF_8)));
    if (!digest.equals(observed)) {
      throw new IllegalStateException("node content differs from its immutable identity");
    }
    return json.readTree(bytes);
  }

  private static boolean neverStarted(JsonNode status) {
    return status.path("imageID").asText().isBlank()
        && status.path("state").path("waiting").isObject()
        && status.path("lastState").path("terminated").isMissingNode();
  }

  private static Map<String, String> requestedImages(JsonNode pod) {
    final Map<String, String> requested = new LinkedHashMap<>();
    for (String field : List.of("containers", "initContainers")) {
      for (var container : pod.path("spec").path(field)) {
        requested.put(container.path("name").asText(), container.path("image").asText());
      }
    }
    return Map.copyOf(requested);
  }
}
