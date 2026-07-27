CREATE SCHEMA IF NOT EXISTS marketdata_publisher;

CREATE TABLE marketdata_publisher.market_snapshots (
    snapshot_id UUID PRIMARY KEY,
    trading_day DATE NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    source_identity VARCHAR(200) NOT NULL,
    source_timestamp_unix_ms BIGINT NOT NULL CHECK (source_timestamp_unix_ms > 0),
    checksum CHAR(64) NOT NULL,
    snapshot_payload BYTEA NOT NULL,
    active BOOLEAN NOT NULL,
    active_trading_day DATE UNIQUE,
    published_at_unix_ms BIGINT NOT NULL CHECK (published_at_unix_ms > 0),
    CONSTRAINT market_snapshots_source_checksum_unique UNIQUE (source_identity, checksum),
    CONSTRAINT market_snapshots_trading_day_version_unique UNIQUE (trading_day, version),
    CONSTRAINT market_snapshots_active_day_consistent CHECK (
        (active AND active_trading_day = trading_day)
        OR (NOT active AND active_trading_day IS NULL)
    )
);

CREATE TABLE marketdata_publisher.outbox (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload BYTEA NOT NULL,
    payload_type VARCHAR(300) NOT NULL,
    headers_json TEXT NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    created_at_unix_ms BIGINT NOT NULL CHECK (created_at_unix_ms > 0)
);
