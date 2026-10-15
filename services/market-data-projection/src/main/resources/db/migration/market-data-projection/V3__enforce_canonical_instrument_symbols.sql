ALTER TABLE market_data_projection.order_book_entries
    ADD CONSTRAINT order_book_entries_symbol_format CHECK (symbol ~ '^[A-Z0-9]{1,12}$');

ALTER TABLE market_data_projection.instrument_market_data
    ADD CONSTRAINT instrument_market_data_symbol_format CHECK (symbol ~ '^[A-Z0-9]{1,12}$');
