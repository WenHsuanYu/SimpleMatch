ALTER TABLE quickfix_gateway.fix_delivery_intents
    ALTER COLUMN order_quantity TYPE BIGINT USING CAST(order_quantity AS BIGINT);
ALTER TABLE quickfix_gateway.fix_delivery_intents
    ADD CONSTRAINT ck_qfg_delivery_order_quantity CHECK (order_quantity > 0);
ALTER TABLE quickfix_gateway.fix_delivery_intents
    DROP CONSTRAINT ck_qfg_delivery_status;
ALTER TABLE quickfix_gateway.fix_delivery_intents
    ADD CONSTRAINT ck_qfg_delivery_status CHECK (
        CASE status
            WHEN 'PENDING' THEN TRUE
            WHEN 'SENT' THEN TRUE
            ELSE FALSE
        END
    );
ALTER TABLE quickfix_gateway.fix_delivery_intents
    ADD CONSTRAINT ck_qfg_delivery_send_state CHECK (
        (status = 'PENDING' AND sent_at_unix_ms IS NULL)
        OR (status = 'SENT' AND sent_at_unix_ms IS NOT NULL)
    );

ALTER TABLE quickfix_gateway.event_log ALTER COLUMN id TYPE BIGINT;
ALTER TABLE quickfix_gateway.messages_log ALTER COLUMN id TYPE BIGINT;

ALTER TABLE quickfix_gateway.matching_consumer_quarantines
    DROP CONSTRAINT ck_qfg_quarantine_status;
ALTER TABLE quickfix_gateway.matching_consumer_quarantines
    ADD CONSTRAINT ck_qfg_quarantine_status CHECK (
        CASE status
            WHEN 'QUARANTINED' THEN TRUE
            WHEN 'RECOVERED' THEN TRUE
            ELSE FALSE
        END
    );
ALTER TABLE quickfix_gateway.matching_consumer_quarantines
    ADD CONSTRAINT ck_qfg_quarantine_timestamps CHECK (
        quarantined_at_unix_ms >= 0
        AND (recovered_at_unix_ms IS NULL OR recovered_at_unix_ms >= 0)
    );
