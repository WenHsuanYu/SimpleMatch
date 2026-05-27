CREATE TABLE IF NOT EXISTS orders (
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
  source_session_id VARCHAR(255) NOT NULL,
  client_order_id VARCHAR(255) NOT NULL,
  created_at_unix_ms BIGINT NOT NULL,
  updated_at_unix_ms BIGINT NOT NULL,
  CONSTRAINT uq_orders_source_session_client_order UNIQUE (source_session_id, client_order_id)
);

CREATE INDEX IF NOT EXISTS idx_orders_symbol_created_at
    ON orders (symbol, created_at_unix_ms);

CREATE INDEX IF NOT EXISTS idx_orders_account_status
    ON orders (account_id, status);

CREATE TABLE IF NOT EXISTS executions (
  exec_id VARCHAR(255) PRIMARY KEY,
  order_id VARCHAR(255) NOT NULL,
  symbol VARCHAR(64) NOT NULL,
  shard_id INTEGER NOT NULL DEFAULT 0,
  fill_qty NUMERIC(38, 8) NOT NULL,
  fill_price NUMERIC(38, 8) NOT NULL,
  liquidity_flag VARCHAR(32),
  created_at_unix_ms BIGINT NOT NULL,
  CONSTRAINT uq_executions_order_exec UNIQUE (order_id, exec_id)
);

CREATE INDEX IF NOT EXISTS idx_executions_symbol_created_at
    ON executions (symbol, created_at_unix_ms);

CREATE TABLE IF NOT EXISTS processed_events (
  consumer_name VARCHAR(255) NOT NULL,
  event_id VARCHAR(255) NOT NULL,
  processed_at_unix_ms BIGINT NOT NULL,
  PRIMARY KEY (consumer_name, event_id)
);