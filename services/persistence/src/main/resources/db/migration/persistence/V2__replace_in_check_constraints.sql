ALTER TABLE persistence.orders
    DROP CONSTRAINT ck_orders_side;

ALTER TABLE persistence.orders
    ADD CONSTRAINT ck_orders_side
        CHECK (
            CASE side
                WHEN 'SIDE_BUY' THEN TRUE
                WHEN 'SIDE_SELL' THEN TRUE
                ELSE FALSE
                END
            );

ALTER TABLE persistence.orders
    DROP CONSTRAINT ck_orders_order_type;

ALTER TABLE persistence.orders
    ADD CONSTRAINT ck_orders_order_type
        CHECK (
            CASE order_type
                WHEN 'ORDER_TYPE_MARKET' THEN TRUE
                WHEN 'ORDER_TYPE_LIMIT' THEN TRUE
                ELSE FALSE
                END
            );

ALTER TABLE persistence.orders
    DROP CONSTRAINT ck_orders_tif;

ALTER TABLE persistence.orders
    ADD CONSTRAINT ck_orders_tif
        CHECK (
            CASE tif
                WHEN 'TIME_IN_FORCE_ROD' THEN TRUE
                WHEN 'TIME_IN_FORCE_IOC' THEN TRUE
                WHEN 'TIME_IN_FORCE_FOK' THEN TRUE
                ELSE FALSE
                END
            );
