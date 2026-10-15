ALTER TABLE market_data_projection.order_book_entries
    ALTER COLUMN symbol TYPE VARCHAR(12);

ALTER TABLE market_data_projection.instrument_market_data
    ALTER COLUMN symbol TYPE VARCHAR(12);

ALTER TABLE market_data_projection.market_data_events_outbox
    ALTER COLUMN message_key TYPE VARCHAR(17);

ALTER TABLE market_data_projection.matching_event_inbox
    ADD CONSTRAINT matching_event_inbox_event_id_sha256 CHECK (OCTET_LENGTH(event_id) = 32);

ALTER TABLE market_data_projection.matching_event_inbox
    ADD CONSTRAINT matching_event_inbox_payload_sha256 CHECK (OCTET_LENGTH(payload_sha256) = 32);

ALTER TABLE market_data_projection.order_book_entries
    ADD CONSTRAINT order_book_entries_venue CHECK (venue_mic IN ('XTAI', 'ROCO'));

ALTER TABLE market_data_projection.order_book_entries
    ADD CONSTRAINT order_book_entries_event_id_sha256 CHECK (OCTET_LENGTH(last_event_id) = 32);

ALTER TABLE market_data_projection.instrument_market_data
    DROP CONSTRAINT instrument_market_data_last_trade_consistent;

ALTER TABLE market_data_projection.instrument_market_data
    ADD CONSTRAINT instrument_market_data_last_trade_consistent CHECK (
        (last_trade_price_units IS NULL AND last_trade_quantity_shares IS NULL)
            OR (last_trade_price_units IS NOT NULL AND last_trade_quantity_shares IS NOT NULL
                AND last_trade_price_units > 0 AND last_trade_quantity_shares > 0)
    );

ALTER TABLE market_data_projection.instrument_market_data
    ADD CONSTRAINT instrument_market_data_venue CHECK (venue_mic IN ('XTAI', 'ROCO'));

ALTER TABLE market_data_projection.instrument_market_data
    ADD CONSTRAINT instrument_market_data_event_id_sha256 CHECK (
        snapshot_event_id IS NULL OR OCTET_LENGTH(snapshot_event_id) = 32
    );

ALTER TABLE market_data_projection.instrument_market_data
    ADD CONSTRAINT instrument_market_data_snapshot_consistent CHECK (
        (snapshot_event_id IS NULL AND snapshot_payload IS NULL
            AND source_partition_id IS NULL AND source_offset_value IS NULL
            AND redis_snapshot_pending = FALSE)
            OR (snapshot_event_id IS NOT NULL AND snapshot_payload IS NOT NULL
                AND source_partition_id IS NOT NULL AND source_offset_value IS NOT NULL
                AND source_partition_id BETWEEN 0 AND 14 AND source_offset_value >= 0)
    );

ALTER TABLE market_data_projection.market_data_events_outbox
    ADD CONSTRAINT market_data_events_outbox_event_id_sha256 CHECK (OCTET_LENGTH(event_id) = 32);

ALTER TABLE market_data_projection.market_data_events_outbox
    ADD CONSTRAINT market_data_events_outbox_source_event_id_sha256 CHECK (
        OCTET_LENGTH(source_matching_event_id) = 32
    );

ALTER TABLE market_data_projection.market_data_events_outbox
    ADD CONSTRAINT market_data_events_outbox_publication_time CHECK (
        published_at_unix_ms IS NULL OR published_at_unix_ms >= 0
    );
