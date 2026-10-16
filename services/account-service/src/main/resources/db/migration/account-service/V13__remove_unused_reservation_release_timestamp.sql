-- The original timestamp check also references the unused release column.
ALTER TABLE account_service.account_reservations
    DROP CONSTRAINT ck_account_reservations_timestamps;

ALTER TABLE account_service.account_reservations
    DROP COLUMN released_at_unix_ms;

ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_timestamps CHECK (
        created_at_unix_ms >= 0 AND updated_at_unix_ms >= created_at_unix_ms);
