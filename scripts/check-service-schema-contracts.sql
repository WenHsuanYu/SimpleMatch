-- Run against the isolated CI database after all service migrations. All probe rows roll back.
\set ON_ERROR_STOP on
BEGIN;

CREATE FUNCTION pg_temp.expect_rejected(statement TEXT, expected_state TEXT) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE actual_state TEXT;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = expected_state THEN
            RETURN;
        END IF;
        RAISE EXCEPTION 'Expected SQLSTATE %, received % for %', expected_state, actual_state, statement;
    END;
    RAISE EXCEPTION 'Invalid data was accepted: %', statement;
END;
$$;

-- UUID projections accept valid facts and reject malformed identities at the database seam.
INSERT INTO query_service.order_read_model VALUES (
    '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
    'XTAI', '2330', 'SIDE_BUY', 'RESTING', 1, repeat('a', 64), 0, 0, 1
);
SELECT pg_temp.expect_rejected(
    'UPDATE query_service.order_read_model SET account_id = ''not-a-uuid''', '22P02');
SELECT pg_temp.expect_rejected(
    'UPDATE query_service.order_read_model SET state = ''UNKNOWN''', '23514');
SELECT pg_temp.expect_rejected(
    'UPDATE query_service.order_read_model SET source_offset_value = -1', '23514');

INSERT INTO query_service.account_summary_read_model VALUES (
    '00000000-0000-0000-0000-000000000002', 'ACCOUNT_LIFECYCLE_STATE_REJECTED',
    0, 0, 'REJECTED', repeat('x', 1024), '00000000-0000-0000-0000-000000000003', 1
);
SELECT pg_temp.expect_rejected(
    'UPDATE query_service.account_summary_read_model SET reserved_notional_units = -1', '23514');

INSERT INTO query_service.active_market_reference (
    trading_day, artifact_id, venue_mic, symbol, updated_at_unix_ms
) VALUES ('2026-10-15', 'artifact', 'XTAI', '2330', 1);
SELECT pg_temp.expect_rejected(
    $sql$INSERT INTO query_service.active_market_reference (
        trading_day, artifact_id, venue_mic, symbol, updated_at_unix_ms
    ) VALUES ('2026-10-15', 'artifact', 'ROCO', '2330', 1)$sql$, '23505');

-- SQL's UNKNOWN value must not admit a half-present trade.
INSERT INTO market_data_projection.instrument_market_data (
    venue_mic, symbol, instrument_sequence, redis_snapshot_pending, updated_at_unix_ms
) VALUES ('XTAI', 'ABCDEFGHIJKL', 1, false, 1);
SELECT pg_temp.expect_rejected(
    'UPDATE market_data_projection.instrument_market_data SET last_trade_quantity_shares = 1', '23514');
UPDATE market_data_projection.instrument_market_data
SET last_trade_price_units = 10000, last_trade_quantity_shares = 1;
SELECT pg_temp.expect_rejected(
    'UPDATE market_data_projection.instrument_market_data SET last_trade_quantity_shares = NULL', '23514');

INSERT INTO market_data_projection.order_book_entries VALUES (
    '00000000-0000-0000-0000-000000000001', 'XTAI', 'ABCDEFGHIJKL', 'B',
    10000, 1, decode(repeat('ab', 32), 'hex')
);
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.order_book_entries
        SET last_event_id = decode(repeat('ab', 31), 'hex')$sql$, '23514');
INSERT INTO market_data_projection.market_data_events_outbox VALUES (
    decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 32), 'hex'),
    'XTAI:ABCDEFGHIJKL', 'marketdata.events', decode('01', 'hex'), 1, NULL
);

-- The legacy projection model is retired, while current facts remain queryable.
DO $$ BEGIN
    IF to_regclass('persistence.orders') IS NOT NULL
        OR to_regclass('persistence.executions') IS NOT NULL
        OR to_regclass('persistence.inbox') IS NOT NULL THEN
        RAISE EXCEPTION 'Retired Persistence tables still exist';
    END IF;
END $$;

INSERT INTO persistence.trades VALUES (
    decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 32), 'hex'),
    '2026-10-15', 'session', 0, 0, 'XTAI', '2330', 1, 10000
);
SELECT pg_temp.expect_rejected('UPDATE persistence.trades SET quantity_shares = 0', '23514');

-- Account accepts explicit business identity/day without obsolete scope fields.
INSERT INTO account_service.account_limits (
    account_id, trading_day, currency, limit_total_notional, reserved_notional,
    utilized_notional, available_notional, updated_at_unix_ms
) VALUES ('00000000-0000-0000-0000-000000000002', '2026-10-15', 'TWD', 100, 0, 0, 100, 1);
INSERT INTO account_service.account_reservations (
    reservation_id, request_id, order_id, account_id, symbol, venue_mic, trading_day,
    side, quantity, remaining_quantity, limit_price, reserved_notional,
    status, created_at_unix_ms, updated_at_unix_ms
) VALUES (
    '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
    '2330', 'XTAI', '2026-10-15', 'SIDE_BUY', 1, 1, 1, 1,
    'RESERVATION_STATUS_ACCEPTED', 1, 1
);
SELECT pg_temp.expect_rejected(
    'UPDATE account_service.account_reservations SET request_id = ''not-a-uuid''', '22P02');
SELECT pg_temp.expect_rejected(
    'UPDATE account_service.account_reservations SET trading_day = NULL', '23502');
SELECT pg_temp.expect_rejected(
    'UPDATE account_service.account_reservations SET venue_mic = ''NONE''', '23514');

-- Risk accepts cancellation without reservation; accepted new orders require one.
INSERT INTO risk_service.admission_journal (
    command_id, order_id, account_id, symbol, venue_mic, side, quantity,
    order_type, tif, trading_day, sender_comp_id, target_comp_id, cl_ord_id,
    routing_partition, state, created_at_unix_ms, updated_at_unix_ms,
    artifact_trading_day, artifact_content_sha256, routing_algorithm_version
) VALUES (
    '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002', '2330', 'XTAI', 'SIDE_BUY', 1,
    'CANCEL', 'CANCEL', '2026-10-15', 'CLIENT', 'SERVER', 'cancel-1',
    0, 'ACCEPTED', 1, 1, '2026-10-15', repeat('a', 64), 'v1'
);
SELECT pg_temp.expect_rejected(
    'UPDATE risk_service.admission_journal SET routing_partition = 15', '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE risk_service.admission_journal
        SET reservation_id = '00000000-0000-0000-0000-000000000001'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE risk_service.admission_journal
        SET order_type = 'ORDER_TYPE_LIMIT', tif = 'TIME_IN_FORCE_ROD'$sql$, '23514');
UPDATE risk_service.admission_journal SET order_type = 'ORDER_TYPE_LIMIT',
    tif = 'TIME_IN_FORCE_ROD', reservation_id = '00000000-0000-0000-0000-000000000001';

-- External FIX identity remains text; durable quantity and send-state are constrained.
INSERT INTO quickfix_gateway.fix_delivery_intents (
    delivery_id, event_id, recipient_order_id, delivery_index, source_partition, source_offset,
    session_id, order_id, client_order_id, symbol, side, order_quantity, exec_id, exec_type,
    ord_status, last_quantity, last_price_units, cumulative_quantity, leaves_quantity,
    average_price_units, text, status, created_at_unix_ms
) VALUES (
    decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 32), 'hex'),
    '00000000-0000-0000-0000-000000000001', 0, 0, 0,
    'FIX.4.4:SERVER->CLIENT', 'O-client-1', 'client-1', '2330', 1, 1, 'exec-1', '0',
    '0', 0, 0, 0, 1, 0, '', 'PENDING', 1
);
SELECT pg_temp.expect_rejected(
    'UPDATE quickfix_gateway.fix_delivery_intents SET order_quantity = 0', '23514');
SELECT pg_temp.expect_rejected(
    'UPDATE quickfix_gateway.fix_delivery_intents SET status = ''SENT''', '23514');
UPDATE quickfix_gateway.fix_delivery_intents SET status = 'SENT', sent_at_unix_ms = 1;

-- The same canonical symbol rules apply at the projection SQL seam.
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.order_book_entries SET symbol = 'abc'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.order_book_entries SET symbol = 'a-b'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.order_book_entries SET symbol = ' '$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.instrument_market_data SET symbol = 'abc'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.instrument_market_data SET symbol = 'a-b'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE market_data_projection.instrument_market_data SET symbol = ' '$sql$, '23514');

-- Persisted outcome facts must be rehydratable by their existing Java domain objects.
SELECT pg_temp.expect_rejected(
    $sql$UPDATE account_service.account_reservations SET reason_code = 'BAD'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE account_service.account_reservations
        SET status = 'RESERVATION_STATUS_REJECTED', reserved_notional = 0$sql$, '23514');
UPDATE account_service.account_reservations
SET status = 'RESERVATION_STATUS_REJECTED', reserved_notional = 0,
    reason_code = 'INVALID_ORDER', reason_text = 'Invalid order facts';
SELECT pg_temp.expect_rejected(
    $sql$UPDATE account_service.account_reservations SET reason_text = ''$sql$, '23514');
UPDATE account_service.account_reservations
SET status = 'RESERVATION_STATUS_RELEASED', remaining_quantity = 0,
    reason_code = 'RELEASED', reason_text = '';

SELECT pg_temp.expect_rejected(
    $sql$UPDATE risk_service.admission_journal SET state = 'REJECTED'$sql$, '23514');
UPDATE risk_service.admission_journal
SET state = 'REJECTED', reason_code = 'INVALID_COMMAND', reason_detail = 'Invalid command facts';
SELECT pg_temp.expect_rejected(
    $sql$UPDATE risk_service.admission_journal SET reason_code = ''$sql$, '23514');

-- Match Java String.isBlank for reason data instead of checking ASCII spaces only.
SELECT pg_temp.expect_rejected(
    $sql$UPDATE risk_service.admission_journal SET reason_code = E'\t\n'$sql$, '23514');
SELECT pg_temp.expect_rejected(
    $sql$UPDATE risk_service.admission_journal SET reason_detail = U&'\3000'$sql$, '23514');
-- NBSP is deliberately nonblank under the existing Java domain rule.
UPDATE risk_service.admission_journal SET reason_detail = U&'\00A0';
UPDATE account_service.account_reservations
SET status = 'RESERVATION_STATUS_REJECTED', remaining_quantity = 1,
    reason_code = 'INVALID_ORDER', reason_text = 'Invalid facts';
SELECT pg_temp.expect_rejected(
    $sql$UPDATE account_service.account_reservations SET reason_text = U&'\3000'$sql$, '23514');
UPDATE account_service.account_reservations
SET status = 'RESERVATION_STATUS_ACCEPTED', reason_code = E'\t\n', reason_text = U&'\3000';
SELECT pg_temp.expect_rejected(
    $sql$UPDATE account_service.account_reservations SET reason_text = U&'\00A0'$sql$, '23514');

ROLLBACK;
