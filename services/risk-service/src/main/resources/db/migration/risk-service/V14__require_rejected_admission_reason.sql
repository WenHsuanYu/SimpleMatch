-- Match AdmissionFailure's String.isBlank semantics, including Unicode whitespace and
-- excluding nonbreaking spaces. Other admission states do not construct AdmissionFailure.
ALTER TABLE risk_service.admission_journal
    ADD CONSTRAINT ck_admission_rejected_reason CHECK (
        CASE state
            WHEN 'REJECTED' THEN
                reason_code ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]'
                AND reason_detail ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]'
            ELSE TRUE
        END);
