package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountLifecycleOutbox;
import com.simplematch.accountservice.authority.AccountOutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;

/**
 * Thin JDBC adapter for account lifecycle outbox rows.
 */
@Repository
public class JdbcAccountOutboxRepository implements AccountOutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the adapter with the account-service datasource.
     */
    public JdbcAccountOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void insert(AccountLifecycleOutbox event) {
        jdbcTemplate.update(
                """
                        INSERT INTO account_service.outbox (
                          event_id, topic, message_key, payload, payload_type, headers_json,
                          aggregate_type, aggregate_id, created_at_unix_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, event.eventId(), event.topic(), event.messageKey(), event.payload(), event.payloadType(),
                event.headersJson(), event.aggregateType(), event.aggregateId(), event.createdAtUnixMs());
    }
}
