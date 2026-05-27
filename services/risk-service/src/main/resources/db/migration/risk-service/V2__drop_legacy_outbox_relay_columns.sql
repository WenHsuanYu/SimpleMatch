
CREATE SCHEMA IF NOT EXISTS risk_service;

CREATE INDEX IF NOT EXISTS idx_outbox_created_at
	ON risk_service.outbox (created_at_unix_ms, id);
DROP INDEX IF EXISTS risk_service.idx_outbox_publishable;

ALTER TABLE risk_service.outbox DROP COLUMN IF EXISTS available_at_unix_ms;
ALTER TABLE risk_service.outbox DROP COLUMN IF EXISTS published_at_unix_ms;
ALTER TABLE risk_service.outbox DROP COLUMN IF EXISTS lease_token;
ALTER TABLE risk_service.outbox DROP COLUMN IF EXISTS lease_expires_at_unix_ms;
ALTER TABLE risk_service.outbox DROP COLUMN IF EXISTS publish_attempts;
ALTER TABLE risk_service.outbox DROP COLUMN IF EXISTS last_error;