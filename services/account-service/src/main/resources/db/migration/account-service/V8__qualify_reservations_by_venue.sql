ALTER TABLE account_service.account_reservations
    ADD COLUMN venue_mic VARCHAR(8);

UPDATE account_service.account_reservations
SET venue_mic = 'LEGACY'
WHERE venue_mic IS NULL;

ALTER TABLE account_service.account_reservations
    ALTER COLUMN venue_mic SET NOT NULL;

ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_venue_mic
        CHECK (LENGTH(TRIM(venue_mic)) > 0);
