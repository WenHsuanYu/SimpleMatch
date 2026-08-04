package com.simplematch.marketdatapublisher.routing;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Thin JDBC adapter for routing-policy outbox rows. */
public final class JdbcRoutingPolicyOutbox implements RoutingPolicyOutbox {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the outbox adapter with the service-owned schema datasource. */
  public JdbcRoutingPolicyOutbox(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void insert(RoutingPolicyOutboxRecord record) {
    final RoutingPolicyOutboxRecord.EventIdentity event = record.eventIdentity();
    final RoutingPolicyOutboxRecord.Destination destination = record.destination();
    final RoutingPolicyOutboxRecord.Payload payload = record.payload();
    final RoutingPolicyOutboxRecord.AggregateReference aggregate = record.aggregateReference();
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.outbox (
          event_id, topic, message_key, payload, payload_type, headers_json, aggregate_type, aggregate_id,
          created_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.eventId(),
        destination.topic(),
        destination.messageKey(),
        payload.bytes(),
        payload.payloadType(),
        payload.headersJson(),
        aggregate.aggregateType(),
        aggregate.aggregateId(),
        record.createdAtUnixMs());
  }
}
