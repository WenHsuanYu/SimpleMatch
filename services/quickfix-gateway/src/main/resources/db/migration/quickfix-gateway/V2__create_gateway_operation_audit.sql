CREATE TABLE quickfix_gateway.gateway_operation_audit
(
    audit_id           UUID        PRIMARY KEY,
    operation          VARCHAR(18) NOT NULL,
    actor              VARCHAR(128) NOT NULL,
    reason             VARCHAR(192) NOT NULL,
    outcome            VARCHAR(8)  NOT NULL,
    gate_state         VARCHAR(20) NOT NULL,
    readiness          VARCHAR(18) NOT NULL,
    recorded_at_unix_ms BIGINT     NOT NULL,
    CONSTRAINT ck_qfg_operation_audit_operation CHECK (
        operation = 'OPEN'
        OR operation = 'PAUSE_NEW_ORDERS'
        OR operation = 'INTERRUPT_MARKET'
        OR operation = 'CLOSE_DAY'
    ),
    CONSTRAINT ck_qfg_operation_audit_actor CHECK (LENGTH(TRIM(actor)) > 0),
    CONSTRAINT ck_qfg_operation_audit_reason CHECK (LENGTH(TRIM(reason)) > 0),
    CONSTRAINT ck_qfg_operation_audit_outcome CHECK (
        outcome = 'ACCEPTED' OR outcome = 'REJECTED'
    ),
    CONSTRAINT ck_qfg_operation_audit_gate_state CHECK (
        gate_state = 'PRE_OPEN'
        OR gate_state = 'OPEN'
        OR gate_state = 'NEW_ORDERS_PAUSED'
        OR gate_state = 'MARKET_INTERRUPTED'
        OR gate_state = 'CLOSED'
    ),
    CONSTRAINT ck_qfg_operation_audit_readiness CHECK (
        readiness = 'OPEN_ELIGIBLE'
        OR readiness = 'PAUSE_REQUIRED'
        OR readiness = 'INTERRUPT_REQUIRED'
    ),
    CONSTRAINT ck_qfg_operation_audit_recorded_at CHECK (recorded_at_unix_ms >= 0)
);

CREATE INDEX idx_qfg_operation_audit_recorded_at
    ON quickfix_gateway.gateway_operation_audit (recorded_at_unix_ms, audit_id);
