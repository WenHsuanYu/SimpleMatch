package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validates the existing redacted schema-v2 publication report consumed by deployed diagnostics.
 */
final class CdcPublicationEvidence {
  private CdcPublicationEvidence() {}

  static void verify(JsonNode evidence) {
    ReportFields.require(
        evidence.path("schema_version").isInt()
            && evidence.path("schema_version").intValue() == 2
            && ReportFields.text(evidence, "status").equals("PASS"),
        "publication evidence contract is invalid");
    ReportFields.text(evidence, "topic");
    ReportFields.require(
        CdcPublicationVerifier.uuid(ReportFields.text(evidence, "event_id")),
        "Invalid publication event identity");
    final JsonNode observed = ReportFields.object(evidence, "observed");
    for (String field : List.of("partition", "offset")) {
      ReportFields.require(
          ReportFields.longInteger(evidence, field) == ReportFields.longInteger(observed, field),
          "publication evidence contract is invalid");
    }
    ReportFields.require(
        ReportFields.longInteger(evidence, "expected_timestamp_unix_ms")
            == ReportFields.longInteger(observed, "timestamp_unix_ms"),
        "publication timestamp mismatch");
    final String keyHash =
        CdcPublicationVerifier.hash(
            (ReportFields.text(evidence, "expected_message_key") + "\n")
                .getBytes(StandardCharsets.UTF_8));
    ReportFields.require(
        keyHash.equals(ReportFields.text(observed, "key_sha256")),
        "publication key digest mismatch");
    for (String field : List.of("payload_sha256", "headers_json_sha256", "event_type")) {
      ReportFields.require(
          ReportFields.text(evidence, "expected_" + field)
              .equals(ReportFields.text(observed, field)),
          "publication evidence contract is invalid");
    }
    ReportFields.require(
        ReportFields.text(evidence, "event_id").equals(ReportFields.text(observed, "event_id"))
            && ReportFields.integer(observed, "header_count") == 7,
        "publication header identity mismatch");
    for (String field :
        List.of("headers_sha256", "key_sha256", "payload_sha256", "headers_json_sha256")) {
      ReportFields.require(
          ReportFields.text(observed, field).matches("[0-9a-f]{64}"), "Invalid publication digest");
    }
    final JsonNode flags = ReportFields.object(evidence, "verification");
    for (String field : List.of("headers_exact", "key_exact", "timestamp_exact", "payload_exact")) {
      ReportFields.flag(flags, field, true);
    }
  }
}
