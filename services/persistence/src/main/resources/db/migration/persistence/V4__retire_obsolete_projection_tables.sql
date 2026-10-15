-- The active consumer writes Matching Event facts and projections exclusively.
DROP TABLE persistence.executions;
DROP TABLE persistence.orders;
DROP TABLE persistence.inbox;

ALTER TABLE persistence.matching_consumer_quarantines
    DROP CONSTRAINT ck_matching_consumer_quarantine_status;
ALTER TABLE persistence.matching_consumer_quarantines
    ADD CONSTRAINT ck_matching_consumer_quarantine_status CHECK (
        CASE status
            WHEN 'QUARANTINED' THEN TRUE
            WHEN 'RECOVERED' THEN TRUE
            ELSE FALSE
        END
    );

ALTER TABLE persistence.matching_consumer_quarantines
    ADD CONSTRAINT ck_matching_consumer_quarantine_recovery CHECK (
        (status = 'QUARANTINED' AND recovered_at_unix_ms IS NULL)
        OR (status = 'RECOVERED' AND recovered_at_unix_ms IS NOT NULL)
    );
