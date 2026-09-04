-- Focused dependency diagnostics need a durable marker that is separate from
-- observer-owned CDC health data. Runtime admission and backpressure code does
-- not read or update this table.
CREATE TABLE risk_service.local_resilience_marker
(
    run_id             VARCHAR(128) PRIMARY KEY,
    marker_value       VARCHAR(128) NOT NULL,
    created_at_unix_ms BIGINT       NOT NULL,
    CONSTRAINT ck_local_resilience_marker_run_id CHECK (LENGTH(TRIM(run_id)) > 0),
    CONSTRAINT ck_local_resilience_marker_value CHECK (LENGTH(TRIM(marker_value)) > 0),
    CONSTRAINT ck_local_resilience_marker_created CHECK (created_at_unix_ms >= 0)
);
