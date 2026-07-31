ALTER TABLE account_service.account_reservations
    DROP CONSTRAINT ck_account_reservations_side;

ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_side
        CHECK (
            CASE side
                WHEN 'SIDE_BUY' THEN TRUE
                WHEN 'SIDE_SELL' THEN TRUE
                ELSE FALSE
                END
            );

ALTER TABLE account_service.account_reservations
    DROP CONSTRAINT ck_account_reservations_status;

ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_status
        CHECK (
            CASE status
                WHEN 'RESERVATION_STATUS_ACCEPTED' THEN TRUE
                WHEN 'RESERVATION_STATUS_REJECTED' THEN TRUE
                WHEN 'RESERVATION_STATUS_RELEASED' THEN TRUE
                WHEN 'RESERVATION_STATUS_APPLIED' THEN TRUE
                ELSE FALSE
                END
            );
