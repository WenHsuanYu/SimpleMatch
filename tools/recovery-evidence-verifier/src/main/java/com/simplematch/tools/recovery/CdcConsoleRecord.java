package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Adapts the retained Kafka console collector to the shared exact-record contract. */
final class CdcConsoleRecord {
  private CdcConsoleRecord() {}

  static JsonNode read(JsonNode probe, JsonNode wire) {
    final String metadata = ReportFields.text(wire, "metadata");
    final ObjectNode record = JsonReports.object();
    record.set("topic", probe.get("topic"));
    record.put("partition", label(metadata, "Partition"));
    record.put("offset", label(metadata, "Offset"));
    record.put("timestamp_unix_ms", label(metadata, "CreateTime"));
    record.put("key_hex", unframe(wire, "key_console_hex"));
    record.put("payload_hex", unframe(wire, "value_console_hex"));
    final var partitions = record.putArray("partitions");
    CdcCommands.snapshot(ReportFields.text(wire, "current_snapshot"))
        .properties()
        .forEach(entry -> partitions.add(Long.parseLong(entry.getKey())));
    final String separator = ReportFields.text(wire, "header_separator");
    final var headers = record.putArray("headers");
    for (String token :
        ReportFields.text(wire, "headers")
            .replace("\t", separator)
            .split(Pattern.quote(separator), -1)) {
      if (!token.isEmpty()) {
        final int colon = token.indexOf(':');
        ReportFields.require(colon > 0, "Malformed CDC header");
        headers
            .addObject()
            .put("name", token.substring(0, colon))
            .put(
                "value_hex",
                HexFormat.of()
                    .formatHex(token.substring(colon + 1).getBytes(StandardCharsets.UTF_8)));
      }
    }
    return record;
  }

  private static long label(String metadata, String name) {
    final var matcher =
        Pattern.compile("(?:^|\\s)" + name + ":([0-9]+)(?=\\s|$)").matcher(metadata);
    ReportFields.require(matcher.find(), "Missing record " + name);
    final long value = Long.parseLong(matcher.group(1));
    ReportFields.require(!matcher.find(), "Duplicate record " + name);
    return value;
  }

  private static String unframe(JsonNode wire, String field) {
    final String value = ReportFields.text(wire, field);
    ReportFields.require(
        value.matches("(?i)([0-9a-f]{2})+0a"), "Invalid console framing: " + field);
    return value.substring(0, value.length() - 2);
  }
}
