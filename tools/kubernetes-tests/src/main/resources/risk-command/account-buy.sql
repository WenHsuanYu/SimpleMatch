INSERT INTO account_service.account_limits (
    account_id, trading_day, currency, limit_total_notional, reserved_notional,
    utilized_notional, available_notional, updated_at_unix_ms, version
) VALUES (
    :'account_id'::uuid, :'trading_day'::date, 'TWD', 99999999999999999999.00000000,
    0, 0, 99999999999999999999.00000000, :now_ms, 0
);
