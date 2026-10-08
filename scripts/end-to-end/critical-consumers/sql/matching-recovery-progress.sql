-- Independent durable observations, after all consumers have crossed the repeat offset.
SELECT jsonb_build_object(
    'partition', :'partition'::integer,
    'persistenceLastProcessedOffset', (SELECT last_processed_offset
        FROM persistence.matching_consumer_progress
        WHERE consumer_name = 'persistence-matching-events' AND partition_id = :'partition'::integer),
    'accountLastProcessedOffset', (SELECT last_processed_offset
        FROM account_service.matching_event_consumer_progress
        WHERE consumer_name = 'account-final-matching-events' AND partition_id = :'partition'::integer),
    'quickfixLastProcessedOffset', (SELECT last_processed_offset
        FROM quickfix_gateway.matching_consumer_progress
        WHERE consumer_name = 'quickfix-final-matching-events' AND partition_id = :'partition'::integer)
);
