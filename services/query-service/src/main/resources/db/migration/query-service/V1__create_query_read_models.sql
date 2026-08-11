CREATE SCHEMA IF NOT EXISTS query_service;

CREATE TABLE query_service.projection_inbox
(
    event_id             VARCHAR(128) PRIMARY KEY,
    source_topic         VARCHAR(128) NOT NULL,
    payload_sha256       BYTEA        NOT NULL,
    partition_id         INTEGER      NOT NULL CHECK (partition_id >= 0),
    offset_value         BIGINT       NOT NULL CHECK (offset_value >= 0),
    received_at_unix_ms  BIGINT       NOT NULL CHECK (received_at_unix_ms >= 0),
    CONSTRAINT projection_inbox_position_unique UNIQUE (source_topic, partition_id, offset_value)
);

CREATE TABLE query_service.projection_checkpoint
(
    source_topic          VARCHAR(128) NOT NULL,
    partition_id          INTEGER      NOT NULL CHECK (partition_id >= 0),
    last_processed_offset BIGINT       NOT NULL CHECK (last_processed_offset >= 0),
    recovery_state        VARCHAR(24)  NOT NULL,
    updated_at_unix_ms    BIGINT       NOT NULL CHECK (updated_at_unix_ms >= 0),
    PRIMARY KEY (source_topic, partition_id)
);

CREATE TABLE query_service.order_read_model
(
    order_id              VARCHAR(64) PRIMARY KEY,
    account_id            VARCHAR(64) NOT NULL,
    venue_mic             VARCHAR(8)  NOT NULL,
    symbol                VARCHAR(32) NOT NULL,
    side                  VARCHAR(16) NOT NULL,
    state                 VARCHAR(32) NOT NULL,
    leaves_quantity_shares BIGINT     NOT NULL CHECK (leaves_quantity_shares >= 0),
    last_event_id         VARCHAR(128) NOT NULL,
    source_partition_id   INTEGER     NOT NULL,
    source_offset_value   BIGINT      NOT NULL,
    updated_at_unix_ms    BIGINT      NOT NULL CHECK (updated_at_unix_ms >= 0)
);

CREATE INDEX order_read_model_account_idx
    ON query_service.order_read_model (account_id, updated_at_unix_ms);

CREATE TABLE query_service.execution_read_model
(
    execution_id             VARCHAR(128) PRIMARY KEY,
    order_id                 VARCHAR(64)  NOT NULL,
    account_id               VARCHAR(64)  NOT NULL,
    venue_mic                VARCHAR(8)   NOT NULL,
    symbol                   VARCHAR(32)  NOT NULL,
    side                     VARCHAR(16)  NOT NULL,
    fill_quantity_shares     BIGINT       NOT NULL CHECK (fill_quantity_shares > 0),
    fill_price_units         BIGINT       NOT NULL CHECK (fill_price_units > 0),
    cumulative_quantity_shares BIGINT     NOT NULL CHECK (cumulative_quantity_shares >= 0),
    leaves_quantity_shares   BIGINT       NOT NULL CHECK (leaves_quantity_shares >= 0),
    average_price_units      BIGINT       NOT NULL CHECK (average_price_units > 0),
    source_event_id          VARCHAR(128) NOT NULL,
    source_partition_id      INTEGER      NOT NULL,
    source_offset_value      BIGINT       NOT NULL,
    executed_at_unix_ms      BIGINT       NOT NULL CHECK (executed_at_unix_ms >= 0)
);

CREATE INDEX execution_read_model_order_idx
    ON query_service.execution_read_model (order_id, executed_at_unix_ms, execution_id);

CREATE TABLE query_service.account_summary_read_model
(
    account_id                VARCHAR(64) PRIMARY KEY,
    lifecycle_state           VARCHAR(32) NOT NULL,
    reserved_notional_units   BIGINT       NOT NULL,
    reserved_quantity_shares  BIGINT       NOT NULL CHECK (reserved_quantity_shares >= 0),
    reason_code               VARCHAR(128) NOT NULL,
    reason_detail             VARCHAR(512) NOT NULL,
    source_event_id           VARCHAR(128) NOT NULL,
    updated_at_unix_ms        BIGINT       NOT NULL CHECK (updated_at_unix_ms >= 0)
);

CREATE TABLE query_service.active_market_reference
(
    trading_day               DATE         NOT NULL,
    artifact_id               VARCHAR(128) NOT NULL,
    venue_mic                 VARCHAR(8)   NOT NULL,
    symbol                    VARCHAR(32)  NOT NULL,
    market_rule_id            VARCHAR(128),
    reference_price_units     BIGINT,
    lower_price_limit_units   BIGINT,
    upper_price_limit_units   BIGINT,
    routing_partition         INTEGER,
    updated_at_unix_ms        BIGINT       NOT NULL CHECK (updated_at_unix_ms >= 0),
    PRIMARY KEY (trading_day, venue_mic, symbol)
);

CREATE INDEX active_market_reference_artifact_idx
    ON query_service.active_market_reference (trading_day, artifact_id);
