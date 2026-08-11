ALTER TABLE risk_service.admission_journal
    ADD COLUMN artifact_trading_day DATE;

ALTER TABLE risk_service.admission_journal
    ADD COLUMN artifact_content_sha256 CHAR(64);

ALTER TABLE risk_service.admission_journal
    ADD COLUMN routing_algorithm_version VARCHAR(128);

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_artifact_route
        CHECK (
            (artifact_trading_day IS NULL
                AND artifact_content_sha256 IS NULL
                AND routing_algorithm_version IS NULL)
            OR
            (artifact_trading_day IS NOT NULL
                AND artifact_content_sha256 IS NOT NULL
                AND CHAR_LENGTH(artifact_content_sha256) = 64
                AND routing_algorithm_version IS NOT NULL
                AND CHAR_LENGTH(TRIM(routing_algorithm_version)) > 0)
        );

INSERT INTO risk_service.cdc_delivery_lag (metric_name, lag_events, updated_at_unix_ms)
SELECT 'matching.commands', 0, 0
WHERE NOT EXISTS (
    SELECT 1
    FROM risk_service.cdc_delivery_lag
    WHERE metric_name = 'matching.commands'
);
