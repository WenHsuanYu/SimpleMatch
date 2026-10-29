package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.integer;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;

/** Owns Connect scenario relationships without repeating preflight or shared CDC rules. */
final class ConnectRecoveryVerifier {
  String verify(Path path) throws IOException {
    final EvidenceFiles files = EvidenceFiles.forReport(path);
    final JsonNode report = JsonReports.read(path);
    require(integer(report, "schema_version") == 3, "Unsupported Connect report schema.");
    require(text(report, "profile").equals("connect-worker-loss"), "Wrong Connect profile.");
    require(text(report, "status").equals("PASSED"), "Connect status is not PASSED.");
    require(text(report, "fault_mode").equals("pod-delete"), "Wrong Connect fault.");
    require(integer(report, "deadline_seconds") > 0 && integer(report, "deadline_seconds") <= 900,
        "Unsupported recovery deadline.");
    require(report.path("failure_reason").isNull() || report.path("failure_reason").isMissingNode(),
        "Passed report contains a failure reason.");
    final JsonNode provenance = files.json("provenance.json");
    require(text(provenance, "status").equals("PASS"), "Preflight did not pass.");
    for (String field : new String[] {
        "cluster", "context", "namespace", "namespace_run_id", "run_id"}) {
      require(text(report, field).equals(text(provenance, field)), "Wrong run identity: " + field);
    }
    require(text(report, "source_revision").equals(text(provenance, "current_commit"))
        && text(report, "source_revision").matches("[0-9a-f]{40}"), "Wrong current source.");
    final ConnectOwners.Owner before = owner(files, "before");
    final ConnectOwners.Owner after = owner(files, "after-reassignment");
    require(owner(files, "before-delete").equals(before),
        "Task owner changed before deletion.");
    ConnectOwners.reassigned(before, after);
    for (JsonNode pod : files.json("connect-pods-after-reassignment.json").path("items")) {
      require(!pod.path("metadata").path("name").asText().equals(before.pod().name())
          && !pod.path("metadata").path("uid").asText().equals(before.pod().uid()),
          "Deleted task owner is still present.");
    }
    ConnectDeletion.verify(files, report, before);
    ConnectPublication.verify(files, text(report, "run_id"));
    return "local/connect-task-reassignment";
  }

  private static ConnectOwners.Owner owner(EvidenceFiles files, String stage)
      throws IOException {
    final ConnectOwners.Owner actual = new ConnectOwners().resolve(
        files.json("connect-status-" + stage + ".json"),
        files.json("connect-pods-" + stage + ".json"));
    require(actual.equals(ConnectOwners.Owner.read(files.json("task-owner-" + stage + ".json"))),
        "Recorded task owner disagrees with Connect observations.");
    return actual;
  }
}
