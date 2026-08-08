ALTER TABLE marketdata_publisher.outbox
    ADD COLUMN created_at TIMESTAMP WITHOUT TIME ZONE;

UPDATE marketdata_publisher.outbox
SET created_at = TIMESTAMP '1970-01-01 00:00:00'
    + (created_at_unix_ms * INTERVAL '0 00:00:00.001' DAY TO SECOND);

ALTER TABLE marketdata_publisher.outbox
    ALTER COLUMN created_at SET NOT NULL;
