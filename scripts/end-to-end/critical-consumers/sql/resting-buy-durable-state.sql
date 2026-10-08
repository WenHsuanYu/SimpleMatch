-- Select only test identities/business facts. Preserve NUMERIC(38, 8) as text.
WITH reservations AS (
    SELECT * FROM account_service.account_reservations WHERE account_id = :'account_id'
), limits AS (
    SELECT * FROM account_service.account_limits
    WHERE account_id = :'account_id' AND trading_day = :'trading_day'::date
        AND scope_type = 'ACCOUNT' AND scope_key = '*' AND currency = 'TWD'
)
SELECT jsonb_build_object(
    'persistence', (
        SELECT jsonb_build_object(
            'orderId', order_id, 'accountId', account_id,
            'venueMic', venue_mic, 'symbol', symbol, 'side', side, 'status', status,
            'cumulativeQuantityShares', cumulative_quantity_shares,
            'leavesQuantityShares', leaves_quantity_shares,
            'lastEventId', encode(last_event_id, 'hex'),
            'projectionCount', (SELECT count(*) FROM persistence.matching_order_projections
                WHERE account_id = :'account_id'::uuid),
            'fillCount', (SELECT count(*) FROM persistence.order_fills
                WHERE order_id = :'order_id'::uuid)
        ) FROM persistence.matching_order_projections WHERE order_id = :'order_id'::uuid
    ),
    'account', (
        SELECT jsonb_build_object(
            'reservationCount', (SELECT count(*) FROM reservations),
            'limitCount', (SELECT count(*) FROM limits),
            'reservationId', r.reservation_id, 'orderId', r.order_id, 'accountId', r.account_id,
            'reservationVersion', r.version, 'limitVersion', l.version,
            'venueMic', r.venue_mic, 'symbol', r.symbol, 'side', r.side,
            'tradingDay', r.trading_day, 'status', r.status,
            'quantity', r.quantity::text, 'remainingQuantity', r.remaining_quantity::text,
            'filledQuantity', r.filled_quantity::text, 'limitPrice', r.limit_price::text,
            'reservedNotional', r.reserved_notional::text,
            'limitReservedNotional', l.reserved_notional::text,
            'limitUtilizedNotional', l.utilized_notional::text,
            'limitTotalNotional', l.limit_total_notional::text,
            'limitAvailableNotional', l.available_notional::text
        ) FROM reservations r CROSS JOIN limits l WHERE r.order_id = :'order_id' LIMIT 1
    ),
    'events', jsonb_build_object(
        'persistenceCount', (SELECT count(*) FROM persistence.matching_event_inbox
            WHERE consumer_name = 'persistence-matching-events' AND event_id = decode(:'event_id', 'hex')),
        'accountCount', (SELECT count(*) FROM account_service.matching_event_inbox
            WHERE consumer_name = 'account-final-matching-events' AND event_id = decode(:'event_id', 'hex')),
        'quickfixCount', (SELECT count(*) FROM quickfix_gateway.matching_event_inbox
            WHERE consumer_name = 'quickfix-final-matching-events' AND event_id = decode(:'event_id', 'hex')),
        'persistencePayloadSha256', (SELECT encode(payload_sha256, 'hex')
            FROM persistence.matching_event_inbox
            WHERE consumer_name = 'persistence-matching-events' AND event_id = decode(:'event_id', 'hex')),
        'accountPayloadSha256', (SELECT encode(payload_sha256, 'hex')
            FROM account_service.matching_event_inbox
            WHERE consumer_name = 'account-final-matching-events' AND event_id = decode(:'event_id', 'hex')),
        'quickfixPayloadSha256', (SELECT encode(payload_sha256, 'hex')
            FROM quickfix_gateway.matching_event_inbox
            WHERE consumer_name = 'quickfix-final-matching-events' AND event_id = decode(:'event_id', 'hex')),
        -- This fresh dedicated namespace must have no active quarantine. An
        -- unrelated quarantined event also blocks the real Gateway in #160.
        'quarantineCount', (
            (SELECT count(*) FROM persistence.matching_consumer_quarantines WHERE status = 'QUARANTINED') +
            (SELECT count(*) FROM account_service.matching_event_consumer_quarantines WHERE status = 'QUARANTINED') +
            (SELECT count(*) FROM quickfix_gateway.matching_consumer_quarantines WHERE status = 'QUARANTINED')
        )
    )
);
