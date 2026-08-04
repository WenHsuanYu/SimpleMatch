CREATE TABLE risk_service.routing_policies
(
    routing_policy_id          UUID PRIMARY KEY,
    source_market_snapshot_id  UUID    NOT NULL,
    trading_day                DATE    NOT NULL,
    effective_from_unix_ms     BIGINT  NOT NULL,
    effective_until_unix_ms    BIGINT  NOT NULL,
    partition_count            INTEGER NOT NULL CHECK (partition_count > 0),
    active                     BOOLEAN NOT NULL,
    received_at_unix_ms        BIGINT  NOT NULL CHECK (received_at_unix_ms > 0),
    CONSTRAINT risk_routing_policy_interval_valid
        CHECK (effective_from_unix_ms < effective_until_unix_ms),
    CONSTRAINT risk_routing_policy_day_start_unique
        UNIQUE (trading_day, effective_from_unix_ms)
);

CREATE TABLE risk_service.routing_policy_assignments
(
    routing_policy_id UUID         NOT NULL,
    symbol            VARCHAR(64)  NOT NULL,
    venue_mic         VARCHAR(4)   NOT NULL,
    routing_partition INTEGER      NOT NULL CHECK (routing_partition >= 0),
    PRIMARY KEY (routing_policy_id, symbol, venue_mic),
    CONSTRAINT risk_routing_policy_assignment_fk
        FOREIGN KEY (routing_policy_id)
        REFERENCES risk_service.routing_policies (routing_policy_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_risk_routing_policy_source
    ON risk_service.routing_policies (source_market_snapshot_id);
