-- Machine codes have an ASCII token grammar. The application validates human detail
-- with String.isBlank(); SQL guarantees only a nonempty detail and keeps NOT NULL.
ALTER TABLE risk_service.admission_journal
    DROP CONSTRAINT ck_admission_rejected_reason;

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_rejected_reason CHECK (
        CASE state
            WHEN 'REJECTED' THEN
                reason_code ~ '^[A-Z][A-Z0-9_]*$'
                AND reason_detail <> ''
            ELSE TRUE
        END);
