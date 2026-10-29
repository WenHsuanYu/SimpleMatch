package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publishes one report only after all captured observations pass the same predicate. */
final class PlacementReportWriter {
  Path write(Path directory, int observedSeconds) throws IOException {
    final EvidenceFiles files = new EvidenceFiles(directory);
    final JsonNode provenance = files.json("provenance.json");
    final ObjectNode report = JsonReports.object();
    report.put("schema_version", 3);
    report.put("profile", "java-placement-serving");
    report.put("status", "PASS");
    for (String field : new String[] {
        "cluster", "context", "namespace", "namespace_run_id", "source_revision"}) {
      report.put(field, ReportFields.text(provenance, field));
    }
    final ObjectNode placement = report.putObject("placement");
    final ObjectNode health = report.putObject("observations");
    final ObjectNode redis = report.putObject("redis_outage");
    redis.put("observed_seconds", observedSeconds);
    for (String stage : new String[] {"baseline", "redis-outage", "restored"}) {
      final String key = stage.equals("redis-outage") ? "outage" : stage;
      placement.set(key, reference(files, "placement/" + stage + ".json"));
      health.set(key, reference(files, "health/" + stage + ".json"));
    }
    for (String stage : new String[] {"before", "during", "after"}) {
      redis.set(stage, reference(files, "placement/redis-" + stage + ".json"));
    }
    final Path temporary = Files.createTempFile(directory, ".placement-", ".json");
    try {
      JsonReports.write(temporary, report);
      new PlacementRecoveryVerifier().verify(temporary);
      return Files.move(temporary, directory.resolve("java-placement-serving.json"),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static JsonNode reference(EvidenceFiles files, String relative) throws IOException {
    final ObjectNode reference = JsonReports.object();
    reference.put("file", relative);
    reference.put("sha256", EvidenceFiles.sha256(files.file(relative)));
    return reference;
  }
}
