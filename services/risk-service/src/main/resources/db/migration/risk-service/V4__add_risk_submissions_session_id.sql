ALTER TABLE risk_service.risk_submissions
  ADD COLUMN session_id VARCHAR(255);

UPDATE risk_service.risk_submissions
SET session_id = ''
WHERE session_id IS NULL;

ALTER TABLE risk_service.risk_submissions
  ALTER COLUMN session_id SET NOT NULL;