package com.simplematch.marketdatapublisher.publication;

import java.sql.Timestamp;
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
    final SnapshotOutboxRecord.EventIdentity event = record.eventIdentity();
    final SnapshotOutboxRecord.Destination destination = record.destination();
    final SnapshotOutboxRecord.Payload payload = record.payload();
    final SnapshotOutboxRecord.AggregateReference aggregate = record.aggregateReference();
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.outbox (
          event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
          aggregate_type, aggregate_id, created_at_unix_ms, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.eventId(),
        destination.topic(),
        destination.messageKey(),
        destination.kafkaPartitionId(),
        payload.bytes(),
        payload.payloadType(),
        payload.headersJson(),
        aggregate.aggregateType(),
        aggregate.aggregateId(),
        record.createdAtUnixMs(),
        Timestamp.from(java.time.Instant.ofEpochMilli(record.createdAtUnixMs())));
  }
}
