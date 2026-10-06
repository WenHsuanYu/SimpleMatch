-- Isolated account owned by this deployment test; never seed a real account.
INSERT INTO account_service.account_limits (
    account_id, scope_type, scope_key, trading_day, currency,
    limit_total_notional, reserved_notional, utilized_notional,
    available_notional, updated_at_unix_ms, version
) VALUES (
    :'account_id', 'ACCOUNT', '*', :'trading_day'::date, 'TWD',
    99999999999999999999.00000000, 0, 0,
    99999999999999999999.00000000, :now_ms, 0
);
