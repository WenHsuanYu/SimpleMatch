ALTER TABLE query_service.active_market_reference
    ALTER COLUMN market_rule_id SET NOT NULL;
ALTER TABLE query_service.active_market_reference
    ALTER COLUMN reference_price_units SET NOT NULL;
ALTER TABLE query_service.active_market_reference
    ALTER COLUMN lower_price_limit_units SET NOT NULL;
ALTER TABLE query_service.active_market_reference
    ALTER COLUMN upper_price_limit_units SET NOT NULL;
ALTER TABLE query_service.active_market_reference
    ALTER COLUMN routing_partition SET NOT NULL;

-- Use the database's native non-whitespace class for the rule identity.
ALTER TABLE query_service.active_market_reference
    ADD CONSTRAINT active_market_reference_market_rule CHECK (market_rule_id ~ '\S');

-- Match FinalArtifactPriceBand for the FINAL, ELIGIBLE instruments installed here.
ALTER TABLE query_service.active_market_reference
    ADD CONSTRAINT active_market_reference_price_band CHECK (
        reference_price_units > 0
            AND lower_price_limit_units > 0
            AND upper_price_limit_units > 0
            AND lower_price_limit_units < reference_price_units
            AND reference_price_units < upper_price_limit_units
    );
ALTER TABLE query_service.active_market_reference
    ADD CONSTRAINT active_market_reference_routing_partition CHECK (
        routing_partition BETWEEN 0 AND 14
    );
