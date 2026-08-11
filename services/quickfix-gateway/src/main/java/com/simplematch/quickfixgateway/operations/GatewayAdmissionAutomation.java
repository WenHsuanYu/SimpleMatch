package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Instant;
import java.time.ZonedDateTime;

/** Applies automatic session-close and readiness protections to the Gateway admission gate. */
final class GatewayAdmissionAutomation {
  private final GatewayAdmissionGate admissionGate;
  private final GatewayOperationalPolicy policy;
  private final GatewayOperationAuditRecorder auditRecorder;

  GatewayAdmissionAutomation(
      GatewayAdmissionGate admissionGate,
      GatewayOperationalPolicy policy,
      GatewayOperationAuditRecorder auditRecorder) {
    this.admissionGate = admissionGate;
    this.policy = policy;
    this.auditRecorder = auditRecorder;
  }

  void apply(TradingSystemStatus status, Instant now) {
    if (!closeIfDue(status, now)) {
      applyReadinessProtection(status, now);
    }
  }

  boolean closeIfDue(TradingSystemStatus status, Instant now) {
    if (!isSessionCloseDue(now) || admissionGate.state() == GatewayAdmissionGate.State.CLOSED) {
      return false;
    }
    admissionGate.closeDay();
    auditRecorder.recordAutomatic(
        GatewayOperation.CLOSE_DAY,
        "SESSION_CLOSE_TIME_REACHED",
        admissionGate.state(),
        status,
        now);
    return true;
  }

  int requiredConsecutiveOpenEligibleChecks() {
    return policy.requiredConsecutiveOpenEligibleChecks();
  }

  private void applyReadinessProtection(TradingSystemStatus status, Instant now) {
    if (admissionGate.state() == GatewayAdmissionGate.State.CLOSED) {
      return;
    }
    if (status.readiness() == TradingReadiness.INTERRUPT_REQUIRED
        && admissionGate.state() != GatewayAdmissionGate.State.MARKET_INTERRUPTED) {
      admissionGate.interruptMarket();
      auditRecorder.recordAutomatic(
          GatewayOperation.INTERRUPT_MARKET,
          primaryReason(status),
          admissionGate.state(),
          status,
          now);
    } else if (status.readiness() == TradingReadiness.PAUSE_REQUIRED
        && admissionGate.state() == GatewayAdmissionGate.State.OPEN) {
      admissionGate.pauseNewOrders(primaryReason(status));
      auditRecorder.recordAutomatic(
          GatewayOperation.PAUSE_NEW_ORDERS,
          primaryReason(status),
          admissionGate.state(),
          status,
          now);
    }
  }

  private boolean isSessionCloseDue(Instant now) {
    return policy.automaticCloseEnabled()
        && !ZonedDateTime.ofInstant(now, policy.sessionZone())
            .toLocalTime()
            .isBefore(policy.sessionCloseTime());
  }

  private String primaryReason(TradingSystemStatus status) {
    return status.reasons().isEmpty() ? status.readiness().name() : status.reasons().getFirst();
  }
}
