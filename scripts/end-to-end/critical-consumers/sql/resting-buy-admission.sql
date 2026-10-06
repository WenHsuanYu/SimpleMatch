-- Read-only evidence for the exact FIX business key. Keep duplicate counts visible.
WITH admission AS (
    SELECT * FROM risk_service.admission_journal
    WHERE account_id = :'account_id'::uuid AND cl_ord_id = :'cl_ord_id'
)
SELECT jsonb_build_object(
    'count', (SELECT count(*) FROM admission),
    'outboxCount', (SELECT count(*) FROM risk_service.outbox
        WHERE topic = 'matching.commands' AND message_key = a.command_id::text),
    'commandId', a.command_id, 'orderId', a.order_id, 'reservationId', a.reservation_id,
    'accountId', a.account_id, 'clOrdId', a.cl_ord_id, 'state', a.state,
    'routingPartition', a.routing_partition, 'venueMic', a.venue_mic, 'symbol', a.symbol,
    'side', a.side, 'quantityShares', a.quantity, 'priceUnits', a.limit_price_units,
    'tradingDay', a.trading_day, 'artifactContentSha256', a.artifact_content_sha256,
    'routingAlgorithmVersion', a.routing_algorithm_version
)
FROM admission a LIMIT 1;
