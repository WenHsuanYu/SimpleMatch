package com.simplematch.quickfixgateway.store;

import com.simplematch.quickfixgateway.operations.GatewayOperationAudit;
import com.simplematch.quickfixgateway.operations.GatewayOperationAuditStore;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Thin JDBC adapter that retains Gateway operational command outcomes in its owned schema. */
public final class JdbcGatewayOperationAuditStore implements GatewayOperationAuditStore {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the Gateway-local audit persistence adapter. */
  public JdbcGatewayOperationAuditStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /** Persists one completed command outcome without owning the caller's transaction boundary. */
  @Override
  public void append(GatewayOperationAudit audit) {
    final GatewayOperationAudit requiredAudit = Objects.requireNonNull(audit, "audit");
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.gateway_operation_audit
            (audit_id, operation, actor, reason, outcome, gate_state, readiness, recorded_at_unix_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        requiredAudit.auditId(),
        requiredAudit.operation().name(),
        requiredAudit.actor(),
        requiredAudit.reason(),
        requiredAudit.outcome().name(),
        requiredAudit.gateState().name(),
        requiredAudit.readiness().name(),
        requiredAudit.recordedAt().toEpochMilli());
  }
}
