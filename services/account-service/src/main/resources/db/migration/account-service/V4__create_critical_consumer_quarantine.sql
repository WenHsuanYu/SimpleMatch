CREATE TABLE account_service.consumer_quarantines
(
    consumer_name            VARCHAR(255) NOT NULL,
    event_id                 VARCHAR(255) NOT NULL,
    topic                    VARCHAR(255) NOT NULL,
    partition_id             INTEGER      NOT NULL,
    offset_value             BIGINT       NOT NULL,
    reason                   TEXT         NOT NULL,
    retry_history            TEXT         NOT NULL,
    recovery_instructions    TEXT         NOT NULL,
    quarantined_at_unix_ms   BIGINT       NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    recovered_at_unix_ms     BIGINT,
    PRIMARY KEY (consumer_name, topic, partition_id, offset_value),
    CONSTRAINT ck_account_quarantine_partition CHECK (partition_id >= 0),
    CONSTRAINT ck_account_quarantine_offset CHECK (offset_value >= 0),
    CONSTRAINT ck_account_quarantine_status CHECK (status IN ('QUARANTINED', 'RECOVERED'))
);

CREATE INDEX idx_account_quarantine_open
    ON account_service.consumer_quarantines (consumer_name, topic, partition_id, status);
