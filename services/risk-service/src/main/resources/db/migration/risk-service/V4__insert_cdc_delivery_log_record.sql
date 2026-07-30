INSERT INTO risk_service.cdc_delivery_lag (metric_name, lag_events, updated_at_unix_ms)
SELECT 'orders.validated', 0, 0
WHERE NOT EXISTS (
    SELECT 1
    FROM risk_service.cdc_delivery_lag
    WHERE metric_name = 'orders.validated'
);
