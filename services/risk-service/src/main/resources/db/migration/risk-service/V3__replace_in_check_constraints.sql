ALTER TABLE risk_service.admission_journal
    DROP CONSTRAINT ck_admission_side;

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_side
        CHECK (
            CASE side
                WHEN 'SIDE_BUY' THEN TRUE
                WHEN 'SIDE_SELL' THEN TRUE
                ELSE FALSE
                END
            );

ALTER TABLE risk_service.admission_journal
    DROP CONSTRAINT ck_admission_state;

ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_state
        CHECK (
            CASE state
                WHEN 'PENDING' THEN TRUE
                WHEN 'ACCEPTED' THEN TRUE
                WHEN 'REJECTED' THEN TRUE
                ELSE FALSE
                END
            );
