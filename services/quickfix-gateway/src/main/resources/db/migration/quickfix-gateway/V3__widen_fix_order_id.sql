-- FIX accepts a 64-character ClOrdID and the external OrderID adds the O- prefix.
ALTER TABLE quickfix_gateway.fix_delivery_intents
    ALTER COLUMN order_id SET DATA TYPE VARCHAR(66);
