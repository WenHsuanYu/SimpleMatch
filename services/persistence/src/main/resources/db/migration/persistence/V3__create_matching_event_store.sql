CREATE TABLE persistence.matching_event_inbox
(
    consumer_name       VARCHAR(64) NOT NULL,
    event_id            BYTEA       NOT NULL,
    payload_sha256      BYTEA       NOT NULL,
    received_at_unix_ms BIGINT      NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_matching_event_inbox_consumer_name
        CHECK (LENGTH(TRIM(consumer_name)) > 0),
    CONSTRAINT ck_matching_event_inbox_event_id
        CHECK (OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_matching_event_inbox_payload_sha256
        CHECK (OCTET_LENGTH(payload_sha256) = 32),
    CONSTRAINT ck_matching_event_inbox_received_at
        CHECK (received_at_unix_ms >= 0)
);

CREATE TABLE persistence.trades
(
    trade_id            BYTEA        PRIMARY KEY,
    event_id            BYTEA        NOT NULL UNIQUE,
    trading_day         DATE         NOT NULL,
    trading_session_id  VARCHAR(64)  NOT NULL,
    partition_id        SMALLINT     NOT NULL,
    source_input_offset BIGINT       NOT NULL,
    venue_mic           CHAR(4)      NOT NULL,
    symbol              VARCHAR(12)  NOT NULL,
    quantity_shares     BIGINT       NOT NULL,
    price_units         BIGINT       NOT NULL,
    CONSTRAINT ck_trades_trade_id CHECK (OCTET_LENGTH(trade_id) = 32),
    CONSTRAINT ck_trades_event_id CHECK (OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_trades_partition CHECK (partition_id BETWEEN 0 AND 14),
    CONSTRAINT ck_trades_source_offset CHECK (source_input_offset >= 0),
    CONSTRAINT ck_trades_identity_text
        CHECK (LENGTH(TRIM(trading_session_id)) > 0 AND LENGTH(TRIM(symbol)) > 0),
    CONSTRAINT ck_trades_quantity CHECK (quantity_shares > 0),
    CONSTRAINT ck_trades_price CHECK (price_units > 0)
);

CREATE TABLE persistence.order_fills
(
    trade_id                    BYTEA       NOT NULL,
    leg_role                    SMALLINT     NOT NULL,
    order_id                    UUID        NOT NULL,
    account_id                  UUID        NOT NULL,
    side                        SMALLINT    NOT NULL,
    quantity_shares             BIGINT      NOT NULL,
    price_units                 BIGINT      NOT NULL,
    cumulative_quantity_shares  BIGINT      NOT NULL,
    leaves_quantity_shares      BIGINT      NOT NULL,
    PRIMARY KEY (trade_id, leg_role),
    CONSTRAINT ck_order_fills_trade_id CHECK (OCTET_LENGTH(trade_id) = 32),
    CONSTRAINT ck_order_fills_role CHECK (leg_role >= 1 AND leg_role <= 2),
    CONSTRAINT ck_order_fills_side CHECK (side >= 1 AND side <= 2),
    CONSTRAINT ck_order_fills_quantity CHECK (quantity_shares > 0),
    CONSTRAINT ck_order_fills_price CHECK (price_units > 0),
    CONSTRAINT ck_order_fills_cumulative
        CHECK (cumulative_quantity_shares >= quantity_shares),
    CONSTRAINT ck_order_fills_leaves CHECK (leaves_quantity_shares >= 0)
);

CREATE TABLE persistence.matching_order_projections
(
    order_id                    UUID        PRIMARY KEY,
    account_id                  UUID        NOT NULL,
    venue_mic                   CHAR(4)     NOT NULL,
    symbol                      VARCHAR(12) NOT NULL,
    side                        SMALLINT    NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    cumulative_quantity_shares  BIGINT      NOT NULL,
    leaves_quantity_shares      BIGINT      NOT NULL,
    last_event_id               BYTEA       NOT NULL,
    CONSTRAINT ck_matching_order_projection_event_id CHECK (OCTET_LENGTH(last_event_id) = 32),
    CONSTRAINT ck_matching_order_projection_side CHECK (side >= 1 AND side <= 2),
    CONSTRAINT ck_matching_order_projection_status
        CHECK (
            status = 'RESTING'
            OR status = 'PARTIALLY_FILLED'
            OR status = 'FILLED'
            OR status = 'CANCELLED'
            OR status = 'EXPIRED'
        ),
    CONSTRAINT ck_matching_order_projection_cumulative CHECK (cumulative_quantity_shares >= 0),
    CONSTRAINT ck_matching_order_projection_leaves CHECK (leaves_quantity_shares >= 0)
);

CREATE TABLE persistence.matching_consumer_progress
(
    consumer_name              VARCHAR(64) NOT NULL,
    partition_id               SMALLINT    NOT NULL,
    last_processed_offset      BIGINT      NOT NULL,
    updated_at_unix_ms         BIGINT      NOT NULL,
    PRIMARY KEY (consumer_name, partition_id),
    CONSTRAINT ck_matching_consumer_progress_partition CHECK (partition_id BETWEEN 0 AND 14),
    CONSTRAINT ck_matching_consumer_progress_offset CHECK (last_processed_offset >= 0),
    CONSTRAINT ck_matching_consumer_progress_updated_at CHECK (updated_at_unix_ms >= 0)
);

CREATE TABLE persistence.matching_consumer_quarantines
(
    consumer_name             VARCHAR(64)  NOT NULL,
    topic                     VARCHAR(128) NOT NULL,
    partition_id              SMALLINT     NOT NULL,
    offset_value              BIGINT       NOT NULL,
    event_id                  BYTEA,
    payload_sha256            BYTEA        NOT NULL,
    reason                    TEXT         NOT NULL,
    status                    VARCHAR(11)  NOT NULL,
    quarantined_at_unix_ms    BIGINT       NOT NULL,
    recovered_at_unix_ms      BIGINT,
    PRIMARY KEY (consumer_name, topic, partition_id, offset_value),
    CONSTRAINT ck_matching_consumer_quarantine_partition CHECK (partition_id BETWEEN 0 AND 14),
    CONSTRAINT ck_matching_consumer_quarantine_offset CHECK (offset_value >= 0),
    CONSTRAINT ck_matching_consumer_quarantine_event_id
        CHECK (event_id IS NULL OR OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_matching_consumer_quarantine_payload_sha256
        CHECK (OCTET_LENGTH(payload_sha256) = 32),
    CONSTRAINT ck_matching_consumer_quarantine_status
        CHECK (status = 'QUARANTINED' OR status = 'RECOVERED'),
    CONSTRAINT ck_matching_consumer_quarantine_timestamp
        CHECK (
            quarantined_at_unix_ms >= 0
            AND (recovered_at_unix_ms IS NULL OR recovered_at_unix_ms >= quarantined_at_unix_ms)
        )
);
