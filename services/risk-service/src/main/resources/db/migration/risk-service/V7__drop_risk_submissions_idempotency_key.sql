DROP INDEX IF EXISTS risk_service.idx_risk_submissions_idempotency_key;

ALTER TABLE risk_service.risk_submissions
  DROP COLUMN idempotency_key;