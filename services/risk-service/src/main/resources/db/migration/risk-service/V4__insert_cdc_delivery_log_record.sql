INSERT INTO risk_service.cdc_delivery_lag (
    metric_name,
    lag_events,
    updated_at_unix_ms
)
VALUES (
    'orders.validated',
    0,
    0
);