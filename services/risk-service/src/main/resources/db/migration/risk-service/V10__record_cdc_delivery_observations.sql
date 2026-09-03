CREATE TABLE risk_service.cdc_delivery_observation
(
    event_id            UUID         PRIMARY KEY,
    topic               VARCHAR(255) NOT NULL,
    partition_id        INTEGER      NOT NULL,
    kafka_offset        BIGINT       NOT NULL,
    observed_at_unix_ms BIGINT       NOT NULL,
    CONSTRAINT fk_cdc_delivery_observation_outbox
        FOREIGN KEY (event_id) REFERENCES risk_service.outbox (event_id),
    CONSTRAINT ck_cdc_delivery_observation_position
        CHECK (partition_id >= 0 AND kafka_offset >= 0 AND observed_at_unix_ms >= 0)
);

CREATE INDEX idx_cdc_delivery_observation_position
    ON risk_service.cdc_delivery_observation (topic, partition_id, kafka_offset);
