package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;

/** Assembles run identity without copied prerequisite claims or duplicate evidence views. */
final class ConnectReportDraft {
  ObjectNode create(Path directory) throws IOException {
    final EvidenceFiles files = new EvidenceFiles(directory);
    final JsonNode provenance = files.json("provenance.json");
    final ObjectNode report = JsonReports.object();
    report.put("schema_version", 3);
    report.put("profile", "connect-worker-loss");
    report.put("status", "PASSED");
    report.put("fault_mode", "pod-delete");
    for (String field : new String[] {
        "cluster", "context", "namespace", "namespace_run_id", "run_id"}) {
      report.put(field, ReportFields.text(provenance, field));
    }
    report.put("source_revision", ReportFields.text(provenance, "current_commit"));
    report.put("deadline_seconds", ReportFields.integer(provenance, "deadline_seconds"));
    report.put("recovery_deadline_started_at_unix_ms", ReportFields.longInteger(
        files.json("worker-loss.json"), "recovery_deadline_started_at_unix_ms"));
    return report;
  }
}
