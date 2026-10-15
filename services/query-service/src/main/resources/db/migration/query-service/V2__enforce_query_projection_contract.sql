ALTER TABLE query_service.order_read_model
    ALTER COLUMN order_id SET DATA TYPE UUID USING CAST(order_id AS UUID);
ALTER TABLE query_service.order_read_model
    ALTER COLUMN account_id SET DATA TYPE UUID USING CAST(account_id AS UUID);
ALTER TABLE query_service.execution_read_model
    ALTER COLUMN execution_id SET DATA TYPE UUID USING CAST(execution_id AS UUID);
ALTER TABLE query_service.execution_read_model
    ALTER COLUMN order_id SET DATA TYPE UUID USING CAST(order_id AS UUID);
ALTER TABLE query_service.execution_read_model
    ALTER COLUMN account_id SET DATA TYPE UUID USING CAST(account_id AS UUID);
ALTER TABLE query_service.account_summary_read_model
    ALTER COLUMN account_id SET DATA TYPE UUID USING CAST(account_id AS UUID);
ALTER TABLE query_service.account_summary_read_model
    ALTER COLUMN reason_code SET DATA TYPE VARCHAR(255);
ALTER TABLE query_service.account_summary_read_model
    ALTER COLUMN reason_detail SET DATA TYPE TEXT;

ALTER TABLE query_service.order_read_model
    ALTER COLUMN venue_mic SET DATA TYPE VARCHAR(4);
ALTER TABLE query_service.order_read_model
    ALTER COLUMN symbol SET DATA TYPE VARCHAR(12);
ALTER TABLE query_service.execution_read_model
    ALTER COLUMN venue_mic SET DATA TYPE VARCHAR(4);
ALTER TABLE query_service.execution_read_model
    ALTER COLUMN symbol SET DATA TYPE VARCHAR(12);
ALTER TABLE query_service.active_market_reference
    ALTER COLUMN venue_mic SET DATA TYPE VARCHAR(4);
ALTER TABLE query_service.active_market_reference
    ALTER COLUMN symbol SET DATA TYPE VARCHAR(12);

ALTER TABLE query_service.projection_inbox
    ADD CONSTRAINT projection_inbox_sha256_length CHECK (OCTET_LENGTH(payload_sha256) = 32);
ALTER TABLE query_service.projection_checkpoint
    ADD CONSTRAINT projection_checkpoint_recovery_state CHECK (
        CASE recovery_state WHEN 'READY' THEN TRUE WHEN 'GAP_DETECTED' THEN TRUE ELSE FALSE END
    );
ALTER TABLE query_service.order_read_model
    ADD CONSTRAINT order_read_model_side CHECK (
        CASE side WHEN 'SIDE_BUY' THEN TRUE WHEN 'SIDE_SELL' THEN TRUE ELSE FALSE END
    );
ALTER TABLE query_service.order_read_model
    ADD CONSTRAINT order_read_model_state CHECK (
        CASE state
            WHEN 'RESTING' THEN TRUE WHEN 'PARTIALLY_FILLED' THEN TRUE
            WHEN 'FILLED' THEN TRUE WHEN 'CANCELED' THEN TRUE WHEN 'EXPIRED' THEN TRUE
            ELSE FALSE
        END
    );
ALTER TABLE query_service.order_read_model
    ADD CONSTRAINT order_read_model_instrument CHECK (
        CASE venue_mic WHEN 'XTAI' THEN TRUE WHEN 'ROCO' THEN TRUE ELSE FALSE END
            AND symbol ~ '^[A-Z0-9]{1,12}$'
    );
ALTER TABLE query_service.order_read_model
    ADD CONSTRAINT order_read_model_source_position CHECK (
        source_partition_id >= 0 AND source_offset_value >= 0
    );
ALTER TABLE query_service.execution_read_model
    ADD CONSTRAINT execution_read_model_side CHECK (
        CASE side WHEN 'SIDE_BUY' THEN TRUE WHEN 'SIDE_SELL' THEN TRUE ELSE FALSE END
    );
ALTER TABLE query_service.execution_read_model
    ADD CONSTRAINT execution_read_model_instrument CHECK (
        CASE venue_mic WHEN 'XTAI' THEN TRUE WHEN 'ROCO' THEN TRUE ELSE FALSE END
            AND symbol ~ '^[A-Z0-9]{1,12}$'
    );
ALTER TABLE query_service.execution_read_model
    ADD CONSTRAINT execution_read_model_source_position CHECK (
        source_partition_id >= 0 AND source_offset_value >= 0
    );
ALTER TABLE query_service.execution_read_model
    ADD CONSTRAINT execution_read_model_cumulative_quantity CHECK (
        cumulative_quantity_shares >= fill_quantity_shares
    );
ALTER TABLE query_service.account_summary_read_model
    ADD CONSTRAINT account_summary_read_model_lifecycle_state CHECK (
        CASE lifecycle_state
            WHEN 'ACCOUNT_LIFECYCLE_STATE_RESERVED' THEN TRUE
            WHEN 'ACCOUNT_LIFECYCLE_STATE_REJECTED' THEN TRUE
            WHEN 'ACCOUNT_LIFECYCLE_STATE_RELEASED' THEN TRUE
            WHEN 'ACCOUNT_LIFECYCLE_STATE_FILLED' THEN TRUE
            ELSE FALSE
        END
    );
ALTER TABLE query_service.account_summary_read_model
    ADD CONSTRAINT account_summary_read_model_reserved_notional CHECK (reserved_notional_units >= 0);
ALTER TABLE query_service.active_market_reference
    ADD CONSTRAINT active_market_reference_instrument CHECK (
        CASE venue_mic WHEN 'XTAI' THEN TRUE WHEN 'ROCO' THEN TRUE ELSE FALSE END
            AND symbol ~ '^[A-Z0-9]{1,12}$'
    );
ALTER TABLE query_service.active_market_reference
    ADD CONSTRAINT active_market_reference_symbol_unique UNIQUE (trading_day, symbol);
