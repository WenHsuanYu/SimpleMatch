CREATE SCHEMA IF NOT EXISTS quickfix_gateway;

CREATE TABLE quickfix_gateway.matching_event_inbox
(
    consumer_name       VARCHAR(64) NOT NULL,
    event_id            BYTEA       NOT NULL,
    payload_sha256      BYTEA       NOT NULL,
    raw_payload         BYTEA       NOT NULL,
    received_at_unix_ms BIGINT      NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_qfg_inbox_consumer_name CHECK (LENGTH(TRIM(consumer_name)) > 0),
    CONSTRAINT ck_qfg_inbox_event_id CHECK (OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_qfg_inbox_payload_sha256 CHECK (OCTET_LENGTH(payload_sha256) = 32),
    CONSTRAINT ck_qfg_inbox_received_at CHECK (received_at_unix_ms >= 0)
);

CREATE TABLE quickfix_gateway.fix_delivery_intents
(
    delivery_id             BYTEA        PRIMARY KEY,
    event_id                BYTEA        NOT NULL,
    recipient_order_id      UUID         NOT NULL,
    delivery_index          SMALLINT     NOT NULL,
    source_partition        SMALLINT     NOT NULL,
    source_offset           BIGINT       NOT NULL,
    session_id              VARCHAR(256) NOT NULL,
    order_id                VARCHAR(36)  NOT NULL,
    client_order_id         VARCHAR(64)  NOT NULL,
    symbol                  VARCHAR(12)  NOT NULL,
    side                    SMALLINT     NOT NULL,
    order_quantity          VARCHAR(24)  NOT NULL,
    exec_id                 VARCHAR(80)  NOT NULL,
    exec_type               CHAR(1)      NOT NULL,
    ord_status              CHAR(1)      NOT NULL,
    last_quantity           BIGINT       NOT NULL,
    last_price_units        BIGINT       NOT NULL,
    cumulative_quantity     BIGINT       NOT NULL,
    leaves_quantity         BIGINT       NOT NULL,
    average_price_units     BIGINT       NOT NULL,
    text                    VARCHAR(128) NOT NULL,
    status                  VARCHAR(7)   NOT NULL,
    created_at_unix_ms      BIGINT       NOT NULL,
    sent_at_unix_ms         BIGINT,
    UNIQUE (event_id, recipient_order_id),
    CONSTRAINT ck_qfg_delivery_id CHECK (OCTET_LENGTH(delivery_id) = 32),
    CONSTRAINT ck_qfg_delivery_event_id CHECK (OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_qfg_delivery_index CHECK (delivery_index >= 0 AND delivery_index <= 1),
    CONSTRAINT ck_qfg_delivery_partition CHECK (source_partition BETWEEN 0 AND 14),
    CONSTRAINT ck_qfg_delivery_offset CHECK (source_offset >= 0),
    CONSTRAINT ck_qfg_delivery_session CHECK (LENGTH(TRIM(session_id)) > 0),
    CONSTRAINT ck_qfg_delivery_identity CHECK (
        LENGTH(TRIM(order_id)) > 0
        AND LENGTH(TRIM(client_order_id)) > 0
        AND LENGTH(TRIM(symbol)) > 0
        AND LENGTH(TRIM(exec_id)) > 0
    ),
    CONSTRAINT ck_qfg_delivery_side CHECK (side >= 1 AND side <= 2),
    CONSTRAINT ck_qfg_delivery_quantities CHECK (
        last_quantity >= 0
        AND last_price_units >= 0
        AND cumulative_quantity >= 0
        AND leaves_quantity >= 0
        AND average_price_units >= 0
    ),
    CONSTRAINT ck_qfg_delivery_status CHECK (status = 'PENDING' OR status = 'SENT'),
    CONSTRAINT ck_qfg_delivery_created_at CHECK (created_at_unix_ms >= 0),
    CONSTRAINT ck_qfg_delivery_sent_at CHECK (sent_at_unix_ms IS NULL OR sent_at_unix_ms >= 0)
);

CREATE INDEX idx_qfg_delivery_pending
    ON quickfix_gateway.fix_delivery_intents (status, source_partition, source_offset, delivery_index);

CREATE TABLE quickfix_gateway.matching_consumer_progress
(
    consumer_name        VARCHAR(64) NOT NULL,
    partition_id         SMALLINT    NOT NULL,
    last_processed_offset BIGINT     NOT NULL,
    updated_at_unix_ms   BIGINT      NOT NULL,
    PRIMARY KEY (consumer_name, partition_id),
    CONSTRAINT ck_qfg_progress_partition CHECK (partition_id BETWEEN 0 AND 14),
    CONSTRAINT ck_qfg_progress_offset CHECK (last_processed_offset >= 0),
    CONSTRAINT ck_qfg_progress_updated_at CHECK (updated_at_unix_ms >= 0)
);

CREATE TABLE quickfix_gateway.matching_consumer_quarantines
(
    consumer_name          VARCHAR(64)  NOT NULL,
    topic                  VARCHAR(128) NOT NULL,
    partition_id           SMALLINT     NOT NULL,
    offset_value           BIGINT       NOT NULL,
    event_id               BYTEA,
    payload_sha256         BYTEA        NOT NULL,
    reason                 TEXT         NOT NULL,
    status                 VARCHAR(11)  NOT NULL,
    quarantined_at_unix_ms BIGINT       NOT NULL,
    recovered_at_unix_ms   BIGINT,
    PRIMARY KEY (consumer_name, topic, partition_id, offset_value),
    CONSTRAINT ck_qfg_quarantine_partition CHECK (partition_id BETWEEN 0 AND 14),
    CONSTRAINT ck_qfg_quarantine_offset CHECK (offset_value >= 0),
    CONSTRAINT ck_qfg_quarantine_event_id CHECK (event_id IS NULL OR OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_qfg_quarantine_payload_hash CHECK (OCTET_LENGTH(payload_sha256) = 32),
    CONSTRAINT ck_qfg_quarantine_status CHECK (status = 'QUARANTINED' OR status = 'RECOVERED'),
    CONSTRAINT ck_qfg_quarantine_recovered CHECK (
        (status = 'QUARANTINED' AND recovered_at_unix_ms IS NULL)
        OR (status = 'RECOVERED' AND recovered_at_unix_ms IS NOT NULL)
    )
);

CREATE TABLE quickfix_gateway.sessions
(
    beginstring       CHAR(8)    NOT NULL,
    sendercompid      VARCHAR(64) NOT NULL,
    sendersubid       VARCHAR(64) NOT NULL,
    senderlocid       VARCHAR(64) NOT NULL,
    targetcompid      VARCHAR(64) NOT NULL,
    targetsubid       VARCHAR(64) NOT NULL,
    targetlocid       VARCHAR(64) NOT NULL,
    session_qualifier VARCHAR(64) NOT NULL,
    creation_time     TIMESTAMP   NOT NULL,
    incoming_seqnum   INTEGER     NOT NULL,
    outgoing_seqnum   INTEGER     NOT NULL,
    PRIMARY KEY (
        beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid,
        targetlocid, session_qualifier
    )
);

CREATE TABLE quickfix_gateway.messages
(
    beginstring       CHAR(8)    NOT NULL,
    sendercompid      VARCHAR(64) NOT NULL,
    sendersubid       VARCHAR(64) NOT NULL,
    senderlocid       VARCHAR(64) NOT NULL,
    targetcompid      VARCHAR(64) NOT NULL,
    targetsubid       VARCHAR(64) NOT NULL,
    targetlocid       VARCHAR(64) NOT NULL,
    session_qualifier VARCHAR(64) NOT NULL,
    msgseqnum         INTEGER     NOT NULL,
    message           TEXT        NOT NULL,
    PRIMARY KEY (
        beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid,
        targetlocid, session_qualifier, msgseqnum
    )
);

CREATE SEQUENCE quickfix_gateway.event_log_sequence;

CREATE TABLE quickfix_gateway.event_log
(
    id                INTEGER DEFAULT NEXTVAL('quickfix_gateway.event_log_sequence'),
    time              TIMESTAMP   NOT NULL,
    beginstring       CHAR(8)     NOT NULL,
    sendercompid      VARCHAR(64) NOT NULL,
    sendersubid       VARCHAR(64) NOT NULL,
    senderlocid       VARCHAR(64) NOT NULL,
    targetcompid      VARCHAR(64) NOT NULL,
    targetsubid       VARCHAR(64) NOT NULL,
    targetlocid       VARCHAR(64) NOT NULL,
    session_qualifier VARCHAR(64),
    text              TEXT        NOT NULL,
    PRIMARY KEY (id)
);

CREATE SEQUENCE quickfix_gateway.messages_log_sequence;

CREATE TABLE quickfix_gateway.messages_log
(
    id                INTEGER DEFAULT NEXTVAL('quickfix_gateway.messages_log_sequence'),
    time              TIMESTAMP   NOT NULL,
    beginstring       CHAR(8)     NOT NULL,
    sendercompid      VARCHAR(64) NOT NULL,
    sendersubid       VARCHAR(64) NOT NULL,
    senderlocid       VARCHAR(64) NOT NULL,
    targetcompid      VARCHAR(64) NOT NULL,
    targetsubid       VARCHAR(64) NOT NULL,
    targetlocid       VARCHAR(64) NOT NULL,
    session_qualifier VARCHAR(64),
    text              TEXT        NOT NULL,
    PRIMARY KEY (id)
);
