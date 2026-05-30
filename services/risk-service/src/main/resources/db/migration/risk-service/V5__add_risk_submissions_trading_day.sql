ALTER TABLE risk_service.risk_submissions
  ADD COLUMN trading_day DATE;

UPDATE risk_service.risk_submissions
SET trading_day = DATE '1970-01-01'
WHERE trading_day IS NULL;

ALTER TABLE risk_service.risk_submissions
  ALTER COLUMN trading_day SET NOT NULL;