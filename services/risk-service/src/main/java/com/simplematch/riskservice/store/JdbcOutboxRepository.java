package com.simplematch.riskservice.store;

import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.UUID;

public final class JdbcOutboxRepository implements OutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public void insert(OutboxRecord record) {
        jdbcTemplate.update(
                """
                        INSERT INTO risk_service.outbox (
                            event_id,
                            topic,
                            message_key,
                            kafka_partition_id,
                            payload,
                            payload_type,
                            headers_json,
                            aggregate_type,
                            aggregate_id,
                            created_at_unix_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.fromString(record.eventId()),
                record.topic(),
                record.messageKey(),
                record.kafkaPartitionId(),
                record.payload(),
                record.payloadType(),
                record.headersJson(),
                record.aggregateType(),
                record.aggregateId(),
                record.createdAtUnixMs());
    }
}