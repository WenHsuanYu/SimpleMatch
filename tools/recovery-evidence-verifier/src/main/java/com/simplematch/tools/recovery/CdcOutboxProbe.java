package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Selects an exact durable outbox transition from an owned observation and optional baseline. */
public final class CdcOutboxProbe {
  private CdcOutboxProbe() {}

  /**
   * Selects exactly one new event and builds the existing immutable probe contract.
   *
   * @param request schema, aggregate identity, observed rows and optional pre-transition baseline
   * @return the selected durable probe, including a computed digest of its exact bytes
   * @throws IllegalArgumentException if the scope, baseline or candidate is invalid or ambiguous
   */
  public static JsonNode select(JsonNode request) {
    final String schema = ReportFields.text(request, "schema");
    final String type = ReportFields.text(request, "aggregate_type");
    final String identity = ReportFields.text(request, "aggregate_id");
    final Set<String> before =
        request.has("baseline") ? baseline(request.path("baseline"), request) : Set.of();
    final JsonNode rows = request.path("rows");
    ReportFields.require(rows.isArray(), "Missing durable outbox rows");
    final var candidates = new ArrayList<JsonNode>();
    for (JsonNode row : rows) {
      if (!before.contains(ReportFields.text(row, "event_id"))) {
        candidates.add(row);
      }
    }
    ReportFields.require(
        candidates.size() == 1,
        request.has("baseline")
            ? "expected exactly one post-baseline row"
            : "expected exactly one row");
    final ObjectNode probe = candidates.getFirst().deepCopy();
    ReportFields.require(
        type.equals(ReportFields.text(probe, "aggregate_type"))
            && identity.equals(ReportFields.text(probe, "aggregate_id")),
        "outbox business identity mismatch");
    probe.remove("aggregate_id");
    probe.put("business_identity", identity);
    probe.putNull("reservation_id");
    probe.putNull("account_id");
    if (schema.equals("account_service")) {
      ReportFields.require(
          type.equals("account_reservation"), "Account outbox must reference account_reservation");
      probe.put("reservation_id", identity);
      probe.set("account_id", probe.get("message_key"));
    }
    final byte[] payload = CdcPublicationVerifier.bytes(probe, "payload_hex");
    probe.put("payload_hex", java.util.HexFormat.of().formatHex(payload));
    probe.put("payload_sha256", CdcPublicationVerifier.hash(payload));
    CdcPublicationVerifier.validateProbe(probe);
    return probe;
  }

  static Set<String> baseline(JsonNode baseline, JsonNode scope) {
    ReportFields.require(
        baseline.path("schema_version").isInt() && baseline.path("schema_version").intValue() == 1,
        "outbox baseline contract is invalid");
    for (String field : java.util.List.of("schema", "aggregate_type", "aggregate_id")) {
      ReportFields.require(
          ReportFields.text(scope, field).equals(ReportFields.text(baseline, field)),
          "outbox baseline aggregate identity mismatch");
    }
    final JsonNode values = baseline.path("event_ids");
    ReportFields.require(values.isArray(), "outbox baseline contract is invalid");
    final Set<String> ids = new HashSet<>();
    for (JsonNode value : values) {
      ReportFields.require(
          value.isTextual()
              && CdcPublicationVerifier.uuid(value.textValue())
              && ids.add(value.textValue()),
          "outbox baseline event identities are invalid");
    }
    return Set.copyOf(ids);
  }
}
