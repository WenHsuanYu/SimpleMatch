
CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON outbox (created_at_unix_ms, id);
DROP INDEX IF EXISTS idx_outbox_publishable;

ALTER TABLE outbox DROP COLUMN IF EXISTS available_at_unix_ms;
ALTER TABLE outbox DROP COLUMN IF EXISTS published_at_unix_ms;
ALTER TABLE outbox DROP COLUMN IF EXISTS lease_token;
ALTER TABLE outbox DROP COLUMN IF EXISTS lease_expires_at_unix_ms;
ALTER TABLE outbox DROP COLUMN IF EXISTS publish_attempts;
ALTER TABLE outbox DROP COLUMN IF EXISTS last_error;