package com.simplematch.tools.dependencies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.tools.recovery.CdcOutboxProbe;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads real durable outbox rows and delegates transition selection to the shared verifier. */
final class CdcOutbox {
  private final DataSource source;
  private final ObjectMapper mapper = new ObjectMapper();

  CdcOutbox(DataSource source) {
    this.source = source;
  }

  JsonNode insert(CdcOwner owner, String identity, int version) throws Exception {
    final UUID event = UUID.randomUUID();
    final long timestamp = System.currentTimeMillis();
    final byte[] payload =
        (owner.name() + "-private-payload-" + version).getBytes(StandardCharsets.UTF_8);
    final String sql =
        "INSERT INTO "
            + owner.schema()
            + ".outbox"
            + " (event_id,topic,message_key,kafka_partition_id,payload,payload_type,headers_json,aggregate_type,aggregate_id,created_at_unix_ms,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    try (var connection = source.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, event);
      statement.setString(2, owner.topic());
      statement.setString(3, owner.name().toLowerCase(java.util.Locale.ROOT) + "-account");
      if (owner == CdcOwner.RISK) {
        statement.setInt(4, version % 3);
      } else {
        statement.setNull(4, java.sql.Types.INTEGER);
      }
      statement.setBytes(5, payload);
      statement.setString(6, owner.payloadType);
      statement.setString(7, "{\"trace-id\":\"" + event + "\"}");
      statement.setString(8, owner.aggregateType);
      statement.setString(9, identity);
      statement.setLong(10, timestamp);
      statement.setTimestamp(
          11,
          Timestamp.valueOf(
              LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)));
      statement.executeUpdate();
    }
    return eventScope(owner, identity).put("inserted_event_id", event.toString());
  }

  JsonNode baseline(CdcOwner owner, String identity) throws Exception {
    final ObjectNode result = eventScope(owner, identity).put("schema_version", 1);
    final var ids = result.putArray("event_ids");
    try (var connection = source.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT event_id::text FROM "
                    + owner.schema()
                    + ".outbox WHERE aggregate_type = ? AND aggregate_id = ?")) {
      statement.setString(1, owner.aggregateType);
      statement.setString(2, identity);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          ids.add(rows.getString(1));
        }
      }
    }
    return result;
  }

  JsonNode probe(CdcOwner owner, String identity, JsonNode baseline) throws Exception {
    final ObjectNode request = eventScope(owner, identity);
    if (baseline != null) {
      request.set("baseline", baseline);
    }
    final var observed = request.putArray("rows");
    try (var connection = source.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT"
                    + " event_id::text,topic,message_key,kafka_partition_id,payload,payload_type,headers_json,aggregate_type,aggregate_id,round(extract(epoch"
                    + " from created_at)*1000)::bigint FROM "
                    + owner.schema()
                    + ".outbox WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY"
                    + " created_at,event_id")) {
      statement.setString(1, owner.aggregateType);
      statement.setString(2, identity);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          final ObjectNode row = observed.addObject();
          row.put("event_id", rows.getString(1))
              .put("topic", rows.getString(2))
              .put("message_key", rows.getString(3));
          final Integer partition = (Integer) rows.getObject(4);
          if (partition == null) {
            row.putNull("explicit_partition");
          } else {
            row.put("explicit_partition", partition);
          }
          row.put("payload_hex", java.util.HexFormat.of().formatHex(rows.getBytes(5)));
          row.put("payload_type", rows.getString(6))
              .put("headers_json", rows.getString(7))
              .put("aggregate_type", rows.getString(8))
              .put("aggregate_id", rows.getString(9))
              .put("created_at_unix_ms", rows.getLong(10));
        }
      }
    }
    return CdcOutboxProbe.select(request);
  }

  private ObjectNode eventScope(CdcOwner owner, String identity) {
    return mapper
        .createObjectNode()
        .put("schema", owner.schema())
        .put("aggregate_type", owner.aggregateType)
        .put("aggregate_id", identity);
  }
}
