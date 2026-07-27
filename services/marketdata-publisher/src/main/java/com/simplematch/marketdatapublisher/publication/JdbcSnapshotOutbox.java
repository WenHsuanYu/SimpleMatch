package com.simplematch.marketdatapublisher.publication;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Thin JDBC adapter for snapshot-publication outbox rows. */
public final class JdbcSnapshotOutbox implements SnapshotOutbox {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the outbox adapter with the service-owned schema datasource. */
  public JdbcSnapshotOutbox(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void insert(SnapshotOutboxRecord record) {
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.outbox (
          event_id, topic, message_key, payload, payload_type, headers_json, aggregate_type, aggregate_id,
          created_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.eventId(),
        record.topic(),
        record.messageKey(),
        record.payload(),
        record.payloadType(),
        record.headersJson(),
        record.aggregateType(),
        record.aggregateId(),
        record.createdAtUnixMs());
  }
}
