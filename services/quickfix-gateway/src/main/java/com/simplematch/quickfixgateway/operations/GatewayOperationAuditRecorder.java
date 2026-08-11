package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Instant;
import java.util.UUID;

/** Writes Gateway operation audit facts without exposing persistence to admission policy. */
final class GatewayOperationAuditRecorder {
  private static final String SYSTEM_ACTOR = "system";
  private final GatewayOperationAuditStore auditStore;

  GatewayOperationAuditRecorder(GatewayOperationAuditStore auditStore) {
    this.auditStore = auditStore;
  }

  void record(
      GatewayOperationalCommand command,
      boolean accepted,
      GatewayAdmissionGate.State gateState,
      TradingSystemStatus status,
      Instant occurredAt) {
    append(
        command.operation(),
        command.actor(),
        command.reason(),
        accepted,
        gateState,
        status,
        occurredAt);
  }

  void recordAutomatic(
      GatewayOperation operation,
      String reason,
      GatewayAdmissionGate.State gateState,
      TradingSystemStatus status,
      Instant occurredAt) {
    append(operation, SYSTEM_ACTOR, reason, true, gateState, status, occurredAt);
  }

  private void append(
      GatewayOperation operation,
      String actor,
      String reason,
      boolean accepted,
      GatewayAdmissionGate.State gateState,
      TradingSystemStatus status,
      Instant occurredAt) {
    auditStore.append(
        new GatewayOperationAudit(
            UUID.randomUUID(),
            operation,
            actor,
            reason,
            accepted ? GatewayOperationOutcome.ACCEPTED : GatewayOperationOutcome.REJECTED,
            gateState,
            status.readiness(),
            occurredAt));
  }
}
