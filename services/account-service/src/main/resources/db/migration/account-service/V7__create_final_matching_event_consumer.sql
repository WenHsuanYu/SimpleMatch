CREATE TABLE account_service.matching_event_inbox
(
    consumer_name       VARCHAR(64) NOT NULL,
    event_id            BYTEA       NOT NULL,
    payload_sha256      BYTEA       NOT NULL,
    received_at_unix_ms BIGINT      NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_account_matching_event_inbox_consumer
        CHECK (LENGTH(TRIM(consumer_name)) > 0),
    CONSTRAINT ck_account_matching_event_inbox_event_id
        CHECK (OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_account_matching_event_inbox_payload_sha256
        CHECK (OCTET_LENGTH(payload_sha256) = 32),
    CONSTRAINT ck_account_matching_event_inbox_received_at
        CHECK (received_at_unix_ms >= 0)
);

CREATE TABLE account_service.matching_event_consumer_progress
(
    consumer_name       VARCHAR(64) NOT NULL,
    partition_id        SMALLINT    NOT NULL,
    last_processed_offset BIGINT    NOT NULL,
    updated_at_unix_ms  BIGINT      NOT NULL,
    PRIMARY KEY (consumer_name, partition_id),
    CONSTRAINT ck_account_matching_event_progress_partition
        CHECK (partition_id >= 0 AND partition_id <= 14),
    CONSTRAINT ck_account_matching_event_progress_offset
        CHECK (last_processed_offset >= 0),
    CONSTRAINT ck_account_matching_event_progress_updated_at
        CHECK (updated_at_unix_ms >= 0)
);

CREATE TABLE account_service.matching_event_consumer_quarantines
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
    CONSTRAINT ck_account_matching_event_quarantine_partition
        CHECK (partition_id >= 0 AND partition_id <= 14),
    CONSTRAINT ck_account_matching_event_quarantine_offset CHECK (offset_value >= 0),
    CONSTRAINT ck_account_matching_event_quarantine_event_id
        CHECK (event_id IS NULL OR OCTET_LENGTH(event_id) = 32),
    CONSTRAINT ck_account_matching_event_quarantine_payload_sha256
        CHECK (OCTET_LENGTH(payload_sha256) = 32),
    CONSTRAINT ck_account_matching_event_quarantine_status
        CHECK (status = 'QUARANTINED' OR status = 'RECOVERED'),
    CONSTRAINT ck_account_matching_event_quarantine_timestamp
        CHECK (
            quarantined_at_unix_ms >= 0
            AND (recovered_at_unix_ms IS NULL OR recovered_at_unix_ms >= quarantined_at_unix_ms)
        )
);
