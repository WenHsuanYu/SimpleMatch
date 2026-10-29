package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.integer;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Links the observed disappearance to the exact task owner and run marker. */
final class ConnectDeletion {
  private ConnectDeletion() {}

  static void verify(EvidenceFiles files, JsonNode report, ConnectOwners.Owner owner)
      throws IOException {
    final String run = text(report, "run_id");
    for (String file : new String[] {
        "pod-pre-delete.json", "pod-patch.json", "pod-delete-precondition.json"}) {
      final JsonNode pod = files.json(file);
      require(text(pod.path("metadata"), "name").equals(owner.pod().name())
          && text(pod.path("metadata"), "uid").equals(owner.pod().uid()),
          "Deletion targeted another Pod identity.");
      require(ConnectOwners.ready(pod), "Task owner was not Ready before deletion.");
      require(pod.path("metadata").path("deletionTimestamp").isMissingNode()
          || pod.path("metadata").path("deletionTimestamp").isNull(),
          "Task owner was already terminating before deletion.");
      if (!file.equals("pod-pre-delete.json")) {
        require(text(pod.path("metadata").path("labels"), "simplematch.io/worker-loss-run")
            .equals(run), "Pod deletion marker belongs to another run.");
      }
    }
    final JsonNode observation = files.json("target-delete-observation.json");
    require(text(observation, "target_pod").equals(owner.pod().name())
        && text(observation, "target_pod_uid").equals(owner.pod().uid()),
        "Deletion observation identifies another Pod.");
    require(text(observation, "outcome").equals("not-found"),
        "Original Pod name is still present.");
    ReportFields.flag(observation, "target_uid_absent", true);
    final JsonNode loss = files.json("worker-loss.json");
    require(text(loss, "fault").equals("pod-delete")
        && text(loss, "target_pod").equals(owner.pod().name())
        && text(loss, "target_pod_uid").equals(owner.pod().uid()), "Wrong Pod fault.");
    require(text(loss, "delete_selector").equals("simplematch.io/worker-loss-run=" + run),
        "Wrong deletion selector.");
    ReportFields.flag(loss, "delete_requested", true);
    require(integer(loss, "selector_target_count") == 1, "Deletion selected more than one Pod.");
    final long started = ReportFields.longInteger(report, "recovery_deadline_started_at_unix_ms");
    require(started > 0
        && ReportFields.longInteger(loss, "recovery_deadline_started_at_unix_ms") == started
        && ReportFields.longInteger(loss, "requested_at_unix_ms") >= started,
        "Fault timing does not match the recovery deadline.");
  }
}
