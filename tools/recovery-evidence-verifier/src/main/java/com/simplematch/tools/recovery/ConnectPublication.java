package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Checks scenario identity and order; the shared CDC validator owns exact record bytes. */
final class ConnectPublication {
  private ConnectPublication() {}

  static void verify(EvidenceFiles files, String run) throws IOException {
    final JsonNode transition = files.json("account-transition.json");
    final JsonNode baseline = files.json("account-outbox-baseline.json");
    final JsonNode probe = files.json("account-outbox-probe.json");
    final JsonNode publication = files.json("account-publication.json");
    final String event = text(transition, "event_id");
    final String aggregate = text(transition, "aggregate_id");
    require(aggregate.equals(run + "-reservation"), "Transition belongs to another run.");
    require(text(baseline, "schema").equals("account_service")
        && text(baseline, "aggregate_type").equals("account_reservation")
        && text(baseline, "aggregate_id").equals(aggregate), "Wrong Account baseline.");
    require(baseline.path("event_ids").isArray(), "Missing baseline event identities.");
    for (JsonNode previous : baseline.path("event_ids")) {
      require(previous.isTextual() && !previous.asText().equals(event),
          "The baseline already contains the transition.");
    }
    final long created = ReportFields.longInteger(transition, "transition_created_at_unix_ms");
    final long reassigned =
        ReportFields.longInteger(transition, "reassignment_observed_at_unix_ms");
    require(created > reassigned
        && reassigned >= ReportFields.longInteger(files.json("worker-loss.json"),
            "requested_at_unix_ms")
        && ReportFields.longInteger(probe, "created_at_unix_ms") == created,
        "Transition did not occur after reassignment.");
    require(text(probe, "business_identity").equals(aggregate)
        && text(probe, "event_id").equals(event)
        && text(probe, "topic").equals("account.lifecycle")
        && text(probe, "payload_type").equals(text(transition, "payload_type")),
        "Probe does not identify the Account transition.");
    require(text(publication, "status").equals("PASS")
        && text(publication, "event_id").equals(event)
        && text(publication, "topic").equals(text(probe, "topic"))
        && text(publication, "expected_message_key").equals(text(probe, "message_key"))
        && ReportFields.longInteger(publication, "expected_timestamp_unix_ms") == created
        && text(publication, "expected_event_type").equals(text(probe, "payload_type"))
        && text(publication, "expected_payload_sha256").equals(text(probe, "payload_sha256"))
        && text(publication, "expected_headers_json_sha256").equals(EvidenceFiles.bytesSha256(
            text(probe, "headers_json").getBytes(StandardCharsets.UTF_8))),
        "Publication evidence belongs to another probe.");
    location(Files.readString(files.file("account-kafka-baseline.tsv")), publication);
  }

  private static void location(String baseline, JsonNode publication) {
    final Map<Long, Long> offsets = new HashMap<>();
    for (String line : baseline.lines().filter(value -> !value.isBlank()).toList()) {
      final String[] fields = line.split("\\t", -1);
      require(fields.length == 2 && fields[0].matches("[0-9]+") && fields[1].matches("[0-9]+"),
          "Malformed Kafka baseline offset.");
      require(offsets.putIfAbsent(Long.parseLong(fields[0]), Long.parseLong(fields[1])) == null,
          "Conflicting Kafka baseline partition.");
    }
    final Long start = offsets.get(ReportFields.longInteger(publication, "partition"));
    require(start != null && ReportFields.longInteger(publication, "offset") >= start,
        "Publication precedes its Kafka baseline.");
  }
}
