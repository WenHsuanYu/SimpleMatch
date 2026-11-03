-- Select the delivery produced by the actual public FIX business key, not a computed ExecID.
SELECT jsonb_build_object(
    'orderId', a.order_id, 'eventId', encode(d.event_id, 'hex'),
    'execId', d.exec_id, 'status', d.status,
    'sourcePartition', d.source_partition, 'sourceOffset', d.source_offset,
    'count', (SELECT count(*) FROM quickfix_gateway.fix_delivery_intents
              WHERE recipient_order_id = a.order_id AND exec_type = :'exec_type')
)
FROM risk_service.admission_journal a
JOIN quickfix_gateway.fix_delivery_intents d ON d.recipient_order_id = a.order_id
WHERE a.account_id = :'account_id'::uuid AND a.cl_ord_id = :'cl_ord_id'
    AND d.exec_type = :'exec_type'
LIMIT 1;
