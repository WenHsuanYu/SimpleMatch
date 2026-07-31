package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountLifecycleOutbox;
import com.simplematch.accountservice.authority.AccountOutboxRepository;
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
                          event_id, topic, message_key, payload, payload_type, headers_json,
                          aggregate_type, aggregate_id, created_at_unix_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.eventIdentity().eventId(),
        event.destination().topic(),
        event.destination().messageKey(),
        event.payload().bytes(),
        event.payload().payloadType(),
        event.payload().headersJson(),
        event.aggregateReference().aggregateType(),
        event.aggregateReference().aggregateId(),
        event.createdAtUnixMs());
  }
}
