-- These explicit ranges match Character.isWhitespace in ReservationOutcome's String.isBlank checks.
-- Nonbreaking spaces remain nonblank; SQL TRIM alone would miss tabs and Unicode whitespace.
ALTER TABLE account_service.account_reservations
    ADD CONSTRAINT ck_account_reservations_outcome_reasons CHECK (
        CASE status
            WHEN 'RESERVATION_STATUS_REJECTED' THEN
                reason_code ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]'
                AND reason_text ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]'
            WHEN 'RESERVATION_STATUS_ACCEPTED' THEN
                NOT (reason_code ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]')
                AND NOT (reason_text ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]')
            WHEN 'RESERVATION_STATUS_APPLIED' THEN
                NOT (reason_code ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]')
                AND NOT (reason_text ~ U&'[^\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]')
            WHEN 'RESERVATION_STATUS_RELEASED' THEN TRUE
            ELSE FALSE
        END);
