-- Account admission v1 is retired. All active identifiers and quantities follow the v2 contract.
ALTER TABLE account_service.account_reservations
    DROP CONSTRAINT ck_account_reservations_identity;

ALTER TABLE account_service.account_reservations
    ALTER COLUMN reservation_id SET DATA TYPE UUID USING CAST(reservation_id AS UUID);
ALTER TABLE account_service.account_reservations
    ALTER COLUMN request_id SET DATA TYPE UUID USING CAST(request_id AS UUID);
ALTER TABLE account_service.account_reservations
    ALTER COLUMN order_id SET DATA TYPE UUID USING CAST(order_id AS UUID);
ALTER TABLE account_service.reservation_request_locks
    ALTER COLUMN request_id SET DATA TYPE UUID USING CAST(request_id AS UUID);

ALTER TABLE account_service.account_limits DROP CONSTRAINT uq_account_limits_scope;
ALTER TABLE account_service.account_limits DROP CONSTRAINT ck_account_limits_scope_type;
ALTER TABLE account_service.account_limits DROP CONSTRAINT ck_account_limits_scope_key;
ALTER TABLE account_service.account_limits DROP COLUMN scope_type;
ALTER TABLE account_service.account_limits DROP COLUMN scope_key;
ALTER TABLE account_service.account_limits
    ADD CONSTRAINT uq_account_limits_account_day UNIQUE (account_id, trading_day);

-- Aggregates sum many reservations and intentionally exceed one signed-long fixed-point value.
ALTER TABLE account_service.account_limits
    ALTER COLUMN limit_total_notional SET DATA TYPE NUMERIC(38, 4);
ALTER TABLE account_service.account_limits
    ALTER COLUMN reserved_notional SET DATA TYPE NUMERIC(38, 4);
ALTER TABLE account_service.account_limits
    ALTER COLUMN utilized_notional SET DATA TYPE NUMERIC(38, 4);
ALTER TABLE account_service.account_limits
    ALTER COLUMN available_notional SET DATA TYPE NUMERIC(38, 4);
ALTER TABLE account_service.account_limits
    ADD CONSTRAINT ck_account_limits_finite_amounts CHECK (
        limit_total_notional <= 9999999999999999999999999999999999.9999
            AND reserved_notional <= 9999999999999999999999999999999999.9999
            AND utilized_notional <= 9999999999999999999999999999999999.9999
            AND available_notional <= 9999999999999999999999999999999999.9999);

ALTER TABLE account_service.account_positions ALTER COLUMN long_qty SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_positions ALTER COLUMN short_qty SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_positions ALTER COLUMN reserved_long_qty SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_positions ALTER COLUMN reserved_short_qty SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_positions ALTER COLUMN symbol SET DATA TYPE VARCHAR(12);
ALTER TABLE account_service.account_positions DROP CONSTRAINT ck_account_positions_symbol;
ALTER TABLE account_service.account_positions
    ADD CONSTRAINT ck_account_positions_symbol CHECK (symbol ~ '^[A-Z0-9]{1,12}$');

ALTER TABLE account_service.account_reservations ALTER COLUMN quantity SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_reservations ALTER COLUMN remaining_quantity SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_reservations ALTER COLUMN filled_quantity SET DATA TYPE BIGINT;
ALTER TABLE account_service.account_reservations ALTER COLUMN limit_price SET DATA TYPE NUMERIC(19, 4);
ALTER TABLE account_service.account_reservations
    ALTER COLUMN reserved_notional SET DATA TYPE NUMERIC(19, 4);
ALTER TABLE account_service.account_reservations ALTER COLUMN trading_day SET NOT NULL;
ALTER TABLE account_service.account_reservations ALTER COLUMN venue_mic SET DATA TYPE VARCHAR(4);
ALTER TABLE account_service.account_reservations ALTER COLUMN symbol SET DATA TYPE VARCHAR(12);
ALTER TABLE account_service.account_reservations DROP CONSTRAINT ck_account_reservations_venue_mic;
ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_venue_mic CHECK (
        CASE venue_mic WHEN 'XTAI' THEN TRUE WHEN 'ROCO' THEN TRUE ELSE FALSE END);
ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_symbol CHECK (symbol ~ '^[A-Z0-9]{1,12}$');
ALTER TABLE account_service.account_reservations DROP CONSTRAINT ck_account_reservations_limit_price;
ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_limit_price CHECK (
        limit_price IS NULL OR (limit_price > 0 AND limit_price <= 922337203685477.5807));
ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_fixed_point_range CHECK (
        reserved_notional <= 922337203685477.5807
            AND (limit_price IS NULL OR quantity * limit_price <= 922337203685477.5807));
ALTER TABLE account_service.account_reservations DROP CONSTRAINT ck_account_reservations_fill_quantities;
ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_fill_quantities CHECK (
        filled_quantity >= 0 AND filled_quantity <= quantity AND remaining_quantity >= 0
            AND CASE status
                WHEN 'RESERVATION_STATUS_ACCEPTED' THEN
                    remaining_quantity > 0 AND remaining_quantity = quantity - filled_quantity
                WHEN 'RESERVATION_STATUS_REJECTED' THEN
                    filled_quantity = 0 AND remaining_quantity = quantity AND reserved_notional = 0
                WHEN 'RESERVATION_STATUS_RELEASED' THEN
                    remaining_quantity = 0 AND reserved_notional = 0
                WHEN 'RESERVATION_STATUS_APPLIED' THEN
                    filled_quantity = quantity AND remaining_quantity = 0 AND reserved_notional = 0
                ELSE FALSE
            END);

ALTER TABLE account_service.consumer_quarantines
    ADD CONSTRAINT ck_account_quarantine_lifecycle CHECK (
        quarantined_at_unix_ms >= 0
            AND ((status = 'QUARANTINED' AND recovered_at_unix_ms IS NULL)
                OR (status = 'RECOVERED' AND recovered_at_unix_ms IS NOT NULL
                    AND recovered_at_unix_ms >= quarantined_at_unix_ms)));
ALTER TABLE account_service.matching_event_consumer_quarantines
    ADD CONSTRAINT ck_account_matching_event_quarantine_lifecycle CHECK (
        (status = 'QUARANTINED' AND recovered_at_unix_ms IS NULL)
            OR (status = 'RECOVERED' AND recovered_at_unix_ms IS NOT NULL));
