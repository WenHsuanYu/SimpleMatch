package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit fact for an operator or automatic Gateway admission command. */
public record GatewayOperationAudit(
    UUID auditId,
    GatewayOperation operation,
    String actor,
    String reason,
    GatewayOperationOutcome outcome,
    GatewayAdmissionGate.State gateState,
    TradingReadiness readiness,
    Instant recordedAt) {
  /** Validates one audit record before its Gateway-owned store accepts it. */
  public GatewayOperationAudit {
    auditId = OperationalStatusValidation.required(auditId, "auditId");
    operation = OperationalStatusValidation.required(operation, "operation");
    actor = OperationalStatusValidation.requiredText(actor, "actor");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
    outcome = OperationalStatusValidation.required(outcome, "outcome");
    gateState = OperationalStatusValidation.required(gateState, "gateState");
    readiness = OperationalStatusValidation.required(readiness, "readiness");
    recordedAt = OperationalStatusValidation.required(recordedAt, "recordedAt");
  }
}
