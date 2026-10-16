-- Application writers validate rejection detail; storage enforces a token and nonempty value.
ALTER TABLE account_service.account_reservations
    DROP CONSTRAINT ck_account_reservations_outcome_reasons;

ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_outcome_reasons CHECK (
        CASE status
            WHEN 'RESERVATION_STATUS_REJECTED' THEN
                reason_code ~ '^[A-Z][A-Z0-9_]*$' AND reason_text <> ''
            WHEN 'RESERVATION_STATUS_ACCEPTED' THEN reason_code = '' AND reason_text = ''
            WHEN 'RESERVATION_STATUS_APPLIED' THEN reason_code = '' AND reason_text = ''
            WHEN 'RESERVATION_STATUS_RELEASED' THEN TRUE
            ELSE FALSE
        END);
