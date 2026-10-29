package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.integer;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Evaluates placement and serving observations across one bounded Redis outage. */
final class PlacementRecoveryVerifier {
  String verify(Path path) throws IOException {
    final EvidenceFiles files = EvidenceFiles.forReport(path);
    final JsonNode report = JsonReports.read(path);
    require(integer(report, "schema_version") == 3, "Unsupported placement report schema.");
    require(text(report, "profile").equals("java-placement-serving"), "Wrong placement profile.");
    require(text(report, "status").equals("PASS"), "Placement status is not PASS.");
    final JsonNode provenance = files.json("provenance.json");
    for (String field : new String[] {
        "cluster", "context", "namespace", "namespace_run_id", "source_revision"}) {
      require(text(report, field).equals(text(provenance, field)), "Ownership changed: " + field);
    }
    require(text(report, "source_revision").matches("[0-9a-f]{40}"), "Invalid source revision.");
    final int seconds = integer(report.path("redis_outage"), "observed_seconds");
    final JsonNode snapshots = report.path("placement");
    final JsonNode baseline = linked(files, snapshots.path("baseline"), "placement/baseline.json");
    PlacementSnapshots.verify(baseline);
    for (String stage : new String[] {"baseline", "redis-outage", "restored"}) {
      final String key = stage.equals("redis-outage") ? "outage" : stage;
      final JsonNode snapshot = linked(files, snapshots.path(key), "placement/" + stage + ".json");
      PlacementSnapshots.verify(snapshot);
      require(text(snapshot, "deployment_image").equals(text(baseline, "deployment_image")),
          "Application image changed during the outage.");
      require(identities(snapshot).equals(identities(baseline)),
          "Application Pod identity or restart count changed.");
      require(integer(snapshot, "liveness_window_seconds") <= seconds,
          "The outage is shorter than the observed liveness window.");
      verifyHealth(files, report.path("observations").path(key), stage);
    }
    verifyRedis(files, report.path("redis_outage"));
    return "local/query-service/redis-outage";
  }

  static JsonNode linked(EvidenceFiles files, JsonNode reference, String expected)
      throws IOException {
    require(text(reference, "file").equals(expected), "Wrong evidence stage: " + expected);
    return files.checked(expected, text(reference, "sha256"));
  }

  private static void verifyHealth(EvidenceFiles files, JsonNode reference, String stage)
      throws IOException {
    final JsonNode observations = linked(files, reference, "health/" + stage + ".json");
    for (String name : new String[] {"readiness", "liveness", "serving"}) {
      final JsonNode observation = observations.path(name);
      require(integer(observation, "http_status") == 200, "Application endpoint is unavailable.");
      require(text(observation, "path").equals(PlacementHealth.path(name)), "Wrong endpoint path.");
      require(text(observation, "authentication").equals(name.equals("serving")
          ? "query-operations-read" : "anonymous-health"), "Wrong endpoint authentication.");
      final String body = "health/" + stage + "-" + name + ".json";
      require(text(observation, "body_file").equals(body), "Wrong endpoint observation stage.");
      PlacementHealth.check(name, files.checked(body, text(observation, "body_sha256")));
    }
  }

  private static void verifyRedis(EvidenceFiles files, JsonNode outage) throws IOException {
    for (String stage : new String[] {"before", "during", "after"}) {
      final JsonNode snapshot = linked(
          files, outage.path(stage), "placement/redis-" + stage + ".json");
      final int expected = stage.equals("during") ? 0 : 1;
      verifyRedisSnapshot(snapshot, expected);
    }
  }

  static void verifyRedisSnapshot(JsonNode snapshot, int expected) {
    require(expected == 0 || expected == 1, "Unsupported Redis replica count.");
    require(text(snapshot, "deployment_name").equals("redis"), "Wrong Redis workload.");
    require(integer(snapshot, "desired_replicas") == expected, "Redis outage was not observed.");
    require(snapshot.path("pods").isArray(), "Missing Redis Pod observations.");
    boolean ready = false;
    for (JsonNode pod : snapshot.path("pods")) {
      ready |= pod.path("ready").asBoolean(false);
    }
    require(ready == (expected == 1), "Redis readiness does not match the outage stage.");
  }

  private static Map<String, PodIdentity> identities(JsonNode snapshot) {
    final Map<String, PodIdentity> result = new TreeMap<>();
    for (JsonNode pod : snapshot.path("pods")) {
      result.put(text(pod, "name"), new PodIdentity(text(pod, "uid"), text(pod, "node"),
          text(pod, "image_id"), integer(pod, "restart_count")));
    }
    return result;
  }

  private record PodIdentity(String uid, String node, String image, int restarts) {}
}
