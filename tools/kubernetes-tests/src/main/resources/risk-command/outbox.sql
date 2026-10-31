SELECT json_build_object(
    'eventId', event_id::text, 'topic', topic, 'messageKey', message_key,
    'partition', kafka_partition_id, 'payloadType', payload_type,
    'payloadBase64', replace(encode(payload, 'base64'), E'\n', ''),
    'aggregateType', aggregate_type, 'aggregateId', aggregate_id
)::text FROM risk_service.outbox
WHERE topic = 'matching.commands' AND message_key = :'command_id'
ORDER BY id DESC LIMIT 1;
