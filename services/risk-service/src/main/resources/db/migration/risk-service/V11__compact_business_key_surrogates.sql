ALTER TABLE risk_service.risk_submissions
  ADD COLUMN business_key_surrogated BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE risk_service.risk_submissions
SET sender_comp_id = CASE
      WHEN sender_comp_id LIKE 'sender_comp_id:%'
        THEN SUBSTRING(sender_comp_id FROM POSITION(':' IN sender_comp_id) + 1)
      ELSE sender_comp_id
    END,
    target_comp_id = CASE
      WHEN target_comp_id LIKE 'target_comp_id:%'
        THEN SUBSTRING(target_comp_id FROM POSITION(':' IN target_comp_id) + 1)
      ELSE target_comp_id
    END,
    cl_ord_id = CASE
      WHEN cl_ord_id LIKE 'cl_ord_id:%'
        THEN SUBSTRING(cl_ord_id FROM POSITION(':' IN cl_ord_id) + 1)
      ELSE cl_ord_id
    END,
    business_key_surrogated = CASE
      WHEN sender_comp_id LIKE 'sender_comp_id:%'
        OR target_comp_id LIKE 'target_comp_id:%'
        OR cl_ord_id LIKE 'cl_ord_id:%'
        THEN TRUE
      ELSE FALSE
    END;

ALTER TABLE risk_service.risk_submissions
  DROP CONSTRAINT risk_submissions_business_key_key;

ALTER TABLE risk_service.risk_submissions
  ADD CONSTRAINT risk_submissions_business_key_key
  UNIQUE (
    sender_comp_id,
    target_comp_id,
    trading_day,
    command_type,
    cl_ord_id,
    business_key_surrogated
  );
