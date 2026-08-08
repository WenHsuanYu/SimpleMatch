package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountLifecycleOutbox;
import com.simplematch.accountservice.authority.AccountOutboxRepository;
import java.sql.Timestamp;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Thin JDBC adapter for account lifecycle outbox rows. */
@Repository
@RequiredArgsConstructor
public class JdbcAccountOutboxRepository implements AccountOutboxRepository {
  @NonNull private final JdbcTemplate jdbcTemplate;

  @Override
  public void insert(AccountLifecycleOutbox event) {
    jdbcTemplate.update(
        """
                        INSERT INTO account_service.outbox (
                          event_id, topic, message_key, kafka_partition_id, payload, payload_type,
                          headers_json, aggregate_type, aggregate_id, created_at_unix_ms, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.eventIdentity().eventId(),
        event.destination().topic(),
        event.destination().messageKey(),
        event.destination().kafkaPartitionId(),
        event.payload().bytes(),
        event.payload().payloadType(),
        event.payload().headersJson(),
        event.aggregateReference().aggregateType(),
        event.aggregateReference().aggregateId(),
        event.createdAtUnixMs(),
        Timestamp.from(java.time.Instant.ofEpochMilli(event.createdAtUnixMs())));
  }
}
