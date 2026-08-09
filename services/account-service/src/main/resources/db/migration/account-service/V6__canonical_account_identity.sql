ALTER TABLE account_service.account_reservations
  DROP CONSTRAINT ck_account_reservations_identity;

ALTER TABLE account_service.account_limits
  ALTER COLUMN account_id SET DATA TYPE UUID USING CAST(account_id AS UUID);

ALTER TABLE account_service.account_positions
  ALTER COLUMN account_id SET DATA TYPE UUID USING CAST(account_id AS UUID);

ALTER TABLE account_service.account_reservations
  ALTER COLUMN account_id SET DATA TYPE UUID USING CAST(account_id AS UUID);

ALTER TABLE account_service.account_reservations
  ADD CONSTRAINT ck_account_reservations_identity CHECK (
    LENGTH(TRIM(reservation_id)) > 0
      AND LENGTH(TRIM(request_id)) > 0
      AND LENGTH(TRIM(order_id)) > 0
      AND LENGTH(TRIM(symbol)) > 0
  );
