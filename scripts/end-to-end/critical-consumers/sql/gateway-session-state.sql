-- Read-only QuickFIX/J continuity evidence. Message bodies never leave PostgreSQL.
WITH observed_session AS (
    SELECT * FROM quickfix_gateway.sessions
    WHERE beginstring = 'FIX.4.4' AND sendercompid = 'SIMPLEMATCH' AND targetcompid = 'CLIENT'
), observed_messages AS (
    SELECT m.msgseqnum AS sequence,
        encode(sha256(convert_to(m.message, 'UTF8')), 'hex') AS sha256
    FROM quickfix_gateway.messages m
    JOIN observed_session s USING (beginstring, sendercompid, sendersubid, senderlocid,
        targetcompid, targetsubid, targetlocid, session_qualifier)
)
SELECT jsonb_build_object(
    'count', (SELECT count(*) FROM observed_session),
    -- CHAR(8) pads BeginString; VARCHAR identity fields must remain exact.
    'identity', jsonb_build_array(s.beginstring::text, s.sendercompid, s.sendersubid, s.senderlocid,
        s.targetcompid, s.targetsubid, s.targetlocid, s.session_qualifier),
    'creationTime', s.creation_time,
    'incomingSequence', s.incoming_seqnum, 'outgoingSequence', s.outgoing_seqnum,
    'messages', (SELECT COALESCE(jsonb_agg(to_jsonb(m) ORDER BY m.sequence), '[]'::jsonb)
        FROM observed_messages m)
)
FROM observed_session s LIMIT 1;
