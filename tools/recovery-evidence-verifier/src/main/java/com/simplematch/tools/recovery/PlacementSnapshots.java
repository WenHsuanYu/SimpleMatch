package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.integer;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Interprets the actual Deployment, Pod, and node observations once. */
final class PlacementSnapshots {
  ObjectNode normalize(Path deployment, Path pods, Path nodes, int plannedSeconds)
      throws IOException {
    final JsonNode application = JsonReports.read(deployment);
    final JsonNode specification = application.path("spec");
    final JsonNode template = specification.path("template").path("spec");
    final JsonNode container = named(template.path("containers"), "query-service");
    for (String probe : new String[] {"startupProbe", "readinessProbe", "livenessProbe"}) {
      final JsonNode http = container.path(probe).path("httpGet");
      require(text(http, "port").equals("http"), "Wrong probe port.");
      require(text(http, "path").equals(probe.equals("livenessProbe")
          ? "/actuator/health/liveness" : "/actuator/health/readiness"), "Wrong probe path.");
    }
    final JsonNode liveness = container.path("livenessProbe");
    final long window = integer(liveness, "initialDelaySeconds")
        + (long) integer(liveness, "periodSeconds") * (integer(liveness, "failureThreshold") + 1L);
    require(integer(liveness, "periodSeconds") > 0
        && integer(liveness, "failureThreshold") > 0 && window <= plannedSeconds,
        "The planned outage is shorter than the actual liveness window.");
    final ObjectNode snapshot = JsonReports.object();
    snapshot.put("deployment_name", text(application.path("metadata"), "name"));
    snapshot.put("deployment_image", text(container, "image"));
    snapshot.put("deployment_node_pool", text(template.path("nodeSelector"),
        "simplematch.io/node-pool"));
    snapshot.put("desired_replicas", integer(specification, "replicas"));
    snapshot.put("liveness_window_seconds", (int) window);
    final ArrayNode eligible = snapshot.putArray("eligible_node_names");
    for (JsonNode node : JsonReports.read(nodes).path("items")) {
      if (node.path("metadata").path("labels").path("simplematch.io/node-pool")
          .asText().equals("local-resilience")) {
        eligible.add(text(node.path("metadata"), "name"));
      }
    }
    final ArrayNode rows = snapshot.putArray("pods");
    for (JsonNode pod : JsonReports.read(pods).path("items")) {
      final JsonNode labels = pod.path("metadata").path("labels");
      if (labels.path("app.kubernetes.io/name").asText().equals("query-service")
          && labels.path("app.kubernetes.io/component").asText().equals("java-service")) {
        rows.add(pod(pod));
      }
    }
    verify(snapshot);
    return snapshot;
  }

  static void verify(JsonNode snapshot) {
    require(text(snapshot, "deployment_name").equals("query-service"), "Wrong application.");
    require(text(snapshot, "deployment_node_pool").equals("local-resilience"), "Wrong node pool.");
    require(integer(snapshot, "desired_replicas") == 2, "Expected two application replicas.");
    integer(snapshot, "liveness_window_seconds");
    final String image = text(snapshot, "deployment_image");
    require(image.matches(".+@sha256:[0-9a-f]{64}"), "Application image is mutable.");
    final String digest = image.substring(image.lastIndexOf('@'));
    final JsonNode pods = snapshot.path("pods");
    require(pods.isArray() && pods.size() == 2, "Expected two application Pods.");
    final Set<String> nodes = new HashSet<>();
    final Set<String> names = new HashSet<>();
    final Set<String> identifiers = new HashSet<>();
    for (JsonNode pod : pods) {
      nodes.add(text(pod, "node"));
      names.add(text(pod, "name"));
      identifiers.add(text(pod, "uid"));
      ReportFields.flag(pod, "ready", true);
      integer(pod, "restart_count");
      require(text(pod, "image_id").endsWith(digest), "Pod image differs from its Deployment.");
    }
    require(nodes.size() == 2 && names.size() == 2 && identifiers.size() == 2,
        "Application Pod identities are not distinct and spread.");
    final Set<String> eligible = new HashSet<>();
    for (JsonNode name : snapshot.path("eligible_node_names")) {
      eligible.add(name.asText());
    }
    require(eligible.containsAll(nodes), "Application Pod is outside the eligible workers.");
  }

  private static JsonNode named(JsonNode values, String name) {
    JsonNode result = null;
    for (JsonNode value : values) {
      if (value.path("name").asText().equals(name)) {
        require(result == null, "Duplicate application container.");
        result = value;
      }
    }
    require(result != null, "Missing application container.");
    return result;
  }

  private static ObjectNode pod(JsonNode pod) {
    final ObjectNode row = JsonReports.object();
    row.put("name", text(pod.path("metadata"), "name"));
    row.put("uid", text(pod.path("metadata"), "uid"));
    row.put("node", text(pod.path("spec"), "nodeName"));
    boolean ready = false;
    for (JsonNode condition : pod.path("status").path("conditions")) {
      ready |= condition.path("type").asText().equals("Ready")
          && condition.path("status").asText().equals("True");
    }
    row.put("ready", ready);
    final JsonNode status = named(pod.path("status").path("containerStatuses"), "query-service");
    row.put("restart_count", integer(status, "restartCount"));
    row.put("image_id", text(status, "imageID"));
    return row;
  }
}
