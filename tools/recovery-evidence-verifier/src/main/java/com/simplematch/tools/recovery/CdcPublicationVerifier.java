package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.longInteger;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks physical Kafka records against durable outbox facts without exposing sensitive bytes. */
public final class CdcPublicationVerifier {
  private static final String HEADER_SEPARATOR = "__SIMPLEMATCH_CDC_HEADER__";
  private static final List<String> IDENTITY_FIELDS =
      List.of(
          "event_id",
          "business_identity",
          "reservation_id",
          "account_id",
          "message_key",
          "topic",
          "payload_type",
          "created_at_unix_ms",
          "headers_json",
          "aggregate_type",
          "explicit_partition");

  private CdcPublicationVerifier() {}

  /**
   * Verifies one record after the captured per-partition baseline and returns schema-v2 evidence.
   *
   * @param probe immutable durable outbox publication facts
   * @param baseline object mapping every topic partition to its captured end offset
   * @param record physical record with exact hex bytes, ordered headers and current partition IDs
   * @return redacted publication evidence
   * @throws IllegalArgumentException if any identity, topology, byte or metadata condition fails
   */
  public static JsonNode verify(JsonNode probe, JsonNode baseline, JsonNode record) {
    validateProbe(probe);
    checkWindow(probe, baseline, record);
    final String event = probe.path("event_id").textValue();
    require(
        Arrays.equals(
            bytes(record, "key_hex"), text(probe, "message_key").getBytes(StandardCharsets.UTF_8)),
        "key bytes mismatch for event " + event);
    require(
        Arrays.equals(bytes(record, "payload_hex"), bytes(probe, "payload_hex")),
        "payload mismatch for event " + event);
    require(
        longInteger(record, "timestamp_unix_ms") == longInteger(probe, "created_at_unix_ms"),
        "timestamp mismatch for event " + event);
    final String headersHash = checkHeaders(probe, record);
    return evidence(probe, record, headersHash);
  }

  /**
   * Rejects any change to a retained durable probe after a recovery operation.
   *
   * @param expected original durable probe
   * @param observed newly read durable probe
   * @throws IllegalArgumentException if identity, metadata or sensitive bytes changed
   */
  public static void sameProbe(JsonNode expected, JsonNode observed) {
    validateProbe(expected);
    validateProbe(observed);
    for (String field : IDENTITY_FIELDS) {
      final JsonNode left = expected.path(field);
      final JsonNode right = observed.path(field);
      require(
          (left.isNull() || left.isMissingNode())
              ? (right.isNull() || right.isMissingNode())
              : left.equals(right),
          "durable outbox probe changed "
              + (field.equals("headers_json") ? "expected headers after recovery" : field));
    }
    require(
        Arrays.equals(bytes(expected, "payload_hex"), bytes(observed, "payload_hex")),
        "durable outbox event payload changed");
  }

  static void validateProbe(JsonNode probe) {
    require(probe.isObject(), "probe contract is invalid");
    for (String field :
        List.of("business_identity", "message_key", "topic", "payload_type", "headers_json")) {
      text(probe, field);
    }
    require(uuid(text(probe, "event_id")), "probe event identity is not a UUID");
    longInteger(probe, "created_at_unix_ms");
    if (!probe.path("explicit_partition").isNull()
        && !probe.path("explicit_partition").isMissingNode()) {
      longInteger(probe, "explicit_partition");
    }
    require(
        hash(bytes(probe, "payload_hex")).equals(text(probe, "payload_sha256")),
        "probe payload digest does not match its sensitive bytes");
  }

  private static void checkWindow(JsonNode probe, JsonNode baseline, JsonNode record) {
    require(baseline.isObject() && !baseline.isEmpty(), "Kafka baseline is empty or invalid");
    final Set<Long> expected = new HashSet<>();
    baseline
        .properties()
        .forEach(
            entry -> {
              require(entry.getKey().matches("0|[1-9][0-9]*"), "Invalid baseline partition");
              require(
                  entry.getValue().isIntegralNumber()
                      && entry.getValue().canConvertToLong()
                      && entry.getValue().longValue() >= 0,
                  "Invalid baseline offset");
              expected.add(Long.parseLong(entry.getKey()));
            });
    final JsonNode partitions = record.path("partitions");
    require(partitions.isArray(), "Missing current topic partitions");
    final Set<Long> actual = new HashSet<>();
    for (JsonNode partition : partitions) {
      require(
          partition.isIntegralNumber()
              && partition.canConvertToLong()
              && partition.longValue() >= 0,
          "Invalid current partition");
      require(actual.add(partition.longValue()), "Duplicate current partition");
    }
    require(expected.equals(actual), "Kafka partition-set drift");
    final long partition = longInteger(record, "partition");
    require(expected.contains(partition), "Record partition was not observed in baseline");
    require(
        longInteger(record, "offset") >= baseline.path(Long.toString(partition)).longValue(),
        "Record precedes the observation baseline");
    require(text(record, "topic").equals(text(probe, "topic")), "Record topic mismatch");
    if (!probe.path("explicit_partition").isNull()
        && !probe.path("explicit_partition").isMissingNode()) {
      require(partition == longInteger(probe, "explicit_partition"), "explicit partition mismatch");
    }
  }

  private static String checkHeaders(JsonNode probe, JsonNode record) {
    final JsonNode headers = record.path("headers");
    require(
        headers.isArray() && headers.size() == 7,
        "unexpected header count in complete known Debezium 3.6 shape");
    final Map<String, String> values = new HashMap<>();
    final java.util.ArrayList<String> wire = new java.util.ArrayList<>();
    for (JsonNode header : headers) {
      final String name = text(header, "name");
      final byte[] encoded = bytes(header, "value_hex");
      final String value = new String(encoded, StandardCharsets.UTF_8);
      require(
          Arrays.equals(encoded, value.getBytes(StandardCharsets.UTF_8)),
          "Invalid UTF-8 CDC header");
      require(values.put(name, value) == null, "Duplicate CDC header");
      wire.add(name + ":" + value);
    }
    final Map<String, String> fixed =
        Map.of(
            "id",
            text(probe, "event_id"),
            "headers_json",
            text(probe, "headers_json"),
            "eventType",
            text(probe, "payload_type"),
            "__debezium.context.taskId",
            "0",
            "__debezium.context.connectorName",
            "postgresql");
    fixed.forEach(
        (name, value) ->
            require(
                value.equals(values.get(name)), "Unexpected or mismatched CDC header: " + name));
    require(
        values.getOrDefault("__debezium.context.connectorLogicalName", "").length() > 0,
        "Missing Debezium connector logical-name header");
    require(uuid(values.getOrDefault("__debezium.context.runId", "")), "non-UUID Debezium runId");
    return hash(String.join(HEADER_SEPARATOR, wire).getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode evidence(JsonNode probe, JsonNode record, String headersHash) {
    final ObjectNode result = JsonReports.object();
    result.put("schema_version", 2).put("status", "PASS");
    for (String field : List.of("topic", "event_id")) {
      result.set(field, probe.get(field));
    }
    result.set("partition", record.get("partition"));
    result.set("offset", record.get("offset"));
    result.set("expected_message_key", probe.get("message_key"));
    result.set("expected_timestamp_unix_ms", probe.get("created_at_unix_ms"));
    result.put(
        "expected_headers_json_sha256",
        hash(text(probe, "headers_json").getBytes(StandardCharsets.UTF_8)));
    result.set("expected_event_type", probe.get("payload_type"));
    result.set("expected_payload_sha256", probe.get("payload_sha256"));
    final ObjectNode observed = result.putObject("observed");
    observed.set("partition", record.get("partition"));
    observed.set("offset", record.get("offset"));
    observed.set("timestamp_unix_ms", record.get("timestamp_unix_ms"));
    observed.put(
        "key_sha256", hash((text(probe, "message_key") + "\n").getBytes(StandardCharsets.UTF_8)));
    observed.put("payload_sha256", hash(bytes(record, "payload_hex")));
    observed.put("headers_json_sha256", result.path("expected_headers_json_sha256").textValue());
    observed.put("headers_sha256", headersHash);
    observed.set("event_id", probe.get("event_id"));
    observed.set("event_type", probe.get("payload_type"));
    observed.put("header_count", 7);
    final ObjectNode verification = result.putObject("verification");
    for (String field : List.of("headers_exact", "key_exact", "timestamp_exact", "payload_exact")) {
      verification.put(field, true);
    }
    return result;
  }

  static byte[] bytes(JsonNode node, String field) {
    final String value = text(node, field);
    require(value.matches("(?i)([0-9a-f]{2})+"), "Invalid hexadecimal field: " + field);
    return HexFormat.of().parseHex(value);
  }

  static String hash(byte[] value) {
    return EvidenceFiles.bytesSha256(value);
  }

  static boolean uuid(String value) {
    return value.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  }
}
