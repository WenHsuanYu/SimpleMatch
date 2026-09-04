-- The pre-release cutover has no legacy admission rows to preserve. Every journal entry must
-- carry the verified daily artifact route that the Matching command envelope repeats.
ALTER TABLE risk_service.admission_journal
    ALTER COLUMN routing_partition SET NOT NULL;

ALTER TABLE risk_service.admission_journal
    ALTER COLUMN artifact_trading_day SET NOT NULL;

ALTER TABLE risk_service.admission_journal
    ALTER COLUMN artifact_content_sha256 SET NOT NULL;

ALTER TABLE risk_service.admission_journal
    ALTER COLUMN routing_algorithm_version SET NOT NULL;

ALTER TABLE risk_service.admission_journal
    DROP CONSTRAINT ck_admission_artifact_route;

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_artifact_route
        CHECK (
            CHAR_LENGTH(artifact_content_sha256) = 64
            AND CHAR_LENGTH(TRIM(routing_algorithm_version)) > 0
        );

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_routing_partition
        CHECK (routing_partition >= 0);
