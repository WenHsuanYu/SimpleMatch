CREATE TABLE marketdata_publisher.routing_policies
(
    routing_policy_id                UUID PRIMARY KEY,
    source_market_snapshot_id       UUID         NOT NULL,
    trading_day                     DATE         NOT NULL,
    effective_from_unix_ms          BIGINT       NOT NULL,
    effective_until_unix_ms         BIGINT       NOT NULL,
    orders_validated_partition_count INTEGER     NOT NULL CHECK (orders_validated_partition_count > 0),
    active                          BOOLEAN      NOT NULL,
    published_at_unix_ms            BIGINT       NOT NULL CHECK (published_at_unix_ms > 0),
    CONSTRAINT routing_policy_source_snapshot_fk
        FOREIGN KEY (source_market_snapshot_id)
        REFERENCES marketdata_publisher.market_snapshots (snapshot_id),
    CONSTRAINT routing_policy_interval_valid
        CHECK (effective_from_unix_ms < effective_until_unix_ms),
    CONSTRAINT routing_policy_identity_interval_unique
        UNIQUE (trading_day, effective_from_unix_ms)
);

CREATE INDEX routing_policies_source_snapshot_idx
    ON marketdata_publisher.routing_policies (source_market_snapshot_id);

CREATE TABLE marketdata_publisher.routing_policy_assignments
(
    routing_policy_id UUID         NOT NULL,
    symbol            VARCHAR(100) NOT NULL,
    venue_mic         VARCHAR(20)  NOT NULL,
    routing_partition INTEGER      NOT NULL CHECK (routing_partition >= 0),
    PRIMARY KEY (routing_policy_id, symbol, venue_mic),
    CONSTRAINT routing_policy_assignments_policy_fk
        FOREIGN KEY (routing_policy_id)
        REFERENCES marketdata_publisher.routing_policies (routing_policy_id)
        ON DELETE CASCADE
);
