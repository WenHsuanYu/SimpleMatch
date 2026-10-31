SELECT json_build_object(
    'commandId', command_id::text, 'orderId', order_id::text, 'accountId', account_id::text,
    'state', state, 'routingPartition', routing_partition,
    'artifactTradingDay', artifact_trading_day::text,
    'artifactContentSha256', artifact_content_sha256,
    'routingAlgorithmVersion', routing_algorithm_version
)::text FROM risk_service.admission_journal WHERE command_id = :'command_id'::uuid;
