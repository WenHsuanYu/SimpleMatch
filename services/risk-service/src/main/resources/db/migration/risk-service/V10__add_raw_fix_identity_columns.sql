ALTER TABLE risk_service.risk_submissions
  ADD COLUMN raw_cl_ord_id TEXT;

ALTER TABLE risk_service.risk_submissions
  ADD COLUMN raw_orig_cl_ord_id TEXT;

UPDATE risk_service.risk_submissions
SET raw_cl_ord_id = cl_ord_id,
    raw_orig_cl_ord_id = orig_cl_ord_id;

ALTER TABLE risk_service.risk_submissions
  ALTER COLUMN raw_cl_ord_id SET NOT NULL;

ALTER TABLE risk_service.risk_submissions
  ALTER COLUMN raw_orig_cl_ord_id SET NOT NULL;