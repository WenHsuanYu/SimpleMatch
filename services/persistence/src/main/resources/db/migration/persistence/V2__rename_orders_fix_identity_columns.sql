CREATE TABLE persistence.orders_v2 (
  order_id VARCHAR(255) PRIMARY KEY,
  account_id VARCHAR(255) NOT NULL,
  symbol VARCHAR(64) NOT NULL,
  shard_id INTEGER NOT NULL DEFAULT 0,
  side VARCHAR(16) NOT NULL,
  order_type VARCHAR(16) NOT NULL,
  tif VARCHAR(16) NOT NULL,
  qty NUMERIC(38, 8) NOT NULL,
  price NUMERIC(38, 8),
  status VARCHAR(64) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0,
  last_command_id VARCHAR(255),
  sender_comp_id VARCHAR(255) NOT NULL,
  target_comp_id VARCHAR(255) NOT NULL,
  cl_ord_id VARCHAR(255) NOT NULL,
  created_at_unix_ms BIGINT NOT NULL,
  updated_at_unix_ms BIGINT NOT NULL,
  CONSTRAINT uq_orders_sender_target_cl_ord UNIQUE (sender_comp_id, target_comp_id, cl_ord_id)
);

INSERT INTO persistence.orders_v2 (
  order_id,
  account_id,
  symbol,
  shard_id,
  side,
  order_type,
  tif,
  qty,
  price,
  status,
  state_version,
  last_command_id,
  sender_comp_id,
  target_comp_id,
  cl_ord_id,
  created_at_unix_ms,
  updated_at_unix_ms
)
SELECT
  order_id,
  account_id,
  symbol,
  shard_id,
  side,
  order_type,
  tif,
  qty,
  price,
  status,
  state_version,
  last_command_id,
  CASE
    WHEN POSITION(':' IN source_session_id) > 0 AND POSITION('->' IN source_session_id) > POSITION(':' IN source_session_id)
      THEN SUBSTRING(
        source_session_id
        FROM POSITION(':' IN source_session_id) + 1
        FOR POSITION('->' IN source_session_id) - POSITION(':' IN source_session_id) - 1)
    ELSE ''
  END,
  CASE
    WHEN POSITION('->' IN source_session_id) > 0
      THEN SUBSTRING(source_session_id FROM POSITION('->' IN source_session_id) + 2)
    ELSE ''
  END,
  client_order_id,
  created_at_unix_ms,
  updated_at_unix_ms
FROM persistence.orders;

DROP TABLE persistence.orders;

ALTER TABLE persistence.orders_v2 RENAME TO orders;

CREATE INDEX idx_orders_symbol_created_at
  ON persistence.orders (symbol, created_at_unix_ms);

CREATE INDEX idx_orders_account_status
  ON persistence.orders (account_id, status);