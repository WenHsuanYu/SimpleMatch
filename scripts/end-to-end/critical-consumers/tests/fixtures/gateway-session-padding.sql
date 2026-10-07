BEGIN;

INSERT INTO quickfix_gateway.sessions (
    beginstring, sendercompid, sendersubid, senderlocid,
    targetcompid, targetsubid, targetlocid, session_qualifier,
    creation_time, incoming_seqnum, outgoing_seqnum
) VALUES (
    'FIX.4.4', 'SIMPLEMATCH', 'sub ', '', 'CLIENT', '', '', '',
    '2026-08-27 09:00:00', 3, 5
);
