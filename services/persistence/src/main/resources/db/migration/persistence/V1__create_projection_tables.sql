CREATE SCHEMA IF NOT EXISTS persistence;

CREATE TABLE persistence.orders (
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
  CONSTRAINT uq_orders_sender_target_cl_ord UNIQUE (sender_comp_id, target_comp_id, cl_ord_id),
  CONSTRAINT ck_orders_shard CHECK (shard_id >= 0),
  CONSTRAINT ck_orders_side CHECK (side IN ('SIDE_BUY', 'SIDE_SELL')),
  CONSTRAINT ck_orders_order_type CHECK (order_type IN ('ORDER_TYPE_MARKET', 'ORDER_TYPE_LIMIT')),
  CONSTRAINT ck_orders_tif CHECK (tif IN ('TIME_IN_FORCE_ROD', 'TIME_IN_FORCE_IOC', 'TIME_IN_FORCE_FOK')),
  CONSTRAINT ck_orders_quantity CHECK (qty > 0),
  CONSTRAINT ck_orders_price CHECK (price IS NULL OR price >= 0),
  CONSTRAINT ck_orders_status CHECK (LENGTH(TRIM(status)) > 0),
  CONSTRAINT ck_orders_state_version CHECK (state_version >= 0),
  CONSTRAINT ck_orders_timestamps CHECK (created_at_unix_ms >= 0 AND updated_at_unix_ms >= created_at_unix_ms)
);

CREATE TABLE persistence.executions (
  exec_id VARCHAR(255) PRIMARY KEY,
  order_id VARCHAR(255) NOT NULL,
  symbol VARCHAR(64) NOT NULL,
  shard_id INTEGER NOT NULL DEFAULT 0,
  fill_qty NUMERIC(38, 8) NOT NULL,
  fill_price NUMERIC(38, 8) NOT NULL,
  liquidity_flag VARCHAR(32),
  created_at_unix_ms BIGINT NOT NULL,
  CONSTRAINT uq_executions_order_exec UNIQUE (order_id, exec_id),
  CONSTRAINT ck_executions_shard CHECK (shard_id >= 0),
  CONSTRAINT ck_executions_quantity CHECK (fill_qty > 0),
  CONSTRAINT ck_executions_price CHECK (fill_price >= 0),
  CONSTRAINT ck_executions_created_at CHECK (created_at_unix_ms >= 0)
);

CREATE TABLE persistence.inbox (
  consumer_name VARCHAR(255) NOT NULL,
  event_id UUID NOT NULL,
  received_at_unix_ms BIGINT NOT NULL,
  PRIMARY KEY (consumer_name, event_id),
  CONSTRAINT ck_inbox_consumer_name CHECK (LENGTH(TRIM(consumer_name)) > 0),
  CONSTRAINT ck_inbox_received_at CHECK (received_at_unix_ms >= 0)
);
