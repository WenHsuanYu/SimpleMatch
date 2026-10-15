ALTER TABLE risk_service.admission_journal
    ALTER COLUMN symbol TYPE VARCHAR(12);
ALTER TABLE risk_service.admission_journal
    ALTER COLUMN sender_comp_id TYPE VARCHAR(64);
ALTER TABLE risk_service.admission_journal
    ALTER COLUMN target_comp_id TYPE VARCHAR(64);
ALTER TABLE risk_service.admission_journal
    ALTER COLUMN cl_ord_id TYPE VARCHAR(64);

ALTER TABLE risk_service.admission_journal
    DROP CONSTRAINT ck_admission_timestamps;
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_timestamps CHECK (
        created_at_unix_ms >= 0 AND updated_at_unix_ms >= created_at_unix_ms
    );

ALTER TABLE risk_service.admission_journal
    DROP CONSTRAINT ck_admission_routing_partition;
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_routing_partition CHECK (routing_partition BETWEEN 0 AND 14);

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_instrument CHECK (
        CASE venue_mic
            WHEN 'XTAI' THEN TRUE
            WHEN 'ROCO' THEN TRUE
            ELSE FALSE
        END
        AND symbol ~ '^[A-Z0-9]{1,12}$'
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_order_type CHECK (
        CASE order_type
            WHEN 'ORDER_TYPE_LIMIT' THEN TRUE
            WHEN 'ORDER_TYPE_MARKET' THEN TRUE
            WHEN 'CANCEL' THEN TRUE
            ELSE FALSE
        END
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_tif CHECK (
        CASE tif
            WHEN 'TIME_IN_FORCE_ROD' THEN TRUE
            WHEN 'TIME_IN_FORCE_IOC' THEN TRUE
            WHEN 'TIME_IN_FORCE_FOK' THEN TRUE
            WHEN 'CANCEL' THEN TRUE
            ELSE FALSE
        END
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_cancel_pair CHECK (
        (order_type = 'CANCEL' AND tif = 'CANCEL')
        OR (order_type <> 'CANCEL' AND tif <> 'CANCEL')
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_limit_price CHECK (
        limit_price_units IS NULL OR limit_price_units > 0
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_artifact_hash CHECK (
        artifact_content_sha256 ~ '^[0-9a-f]{64}$'
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_accepted_reservation CHECK (
        state <> 'ACCEPTED'
        OR (order_type = 'CANCEL' AND reservation_id IS NULL)
        OR (order_type <> 'CANCEL' AND reservation_id IS NOT NULL)
    );
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_fix_identity CHECK (
        LENGTH(TRIM(sender_comp_id)) > 0
        AND LENGTH(TRIM(target_comp_id)) > 0
        AND LENGTH(TRIM(cl_ord_id)) > 0
    );
