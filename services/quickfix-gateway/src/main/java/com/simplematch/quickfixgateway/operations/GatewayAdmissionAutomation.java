package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Instant;
import java.time.ZonedDateTime;

/** Applies automatic session-close and readiness protections to the Gateway admission gate. */
final class GatewayAdmissionAutomation {
  private final GatewayAdmissionGate admissionGate;
  private final GatewayOperationalPolicy policy;
  private final GatewayOperationAuditRecorder auditRecorder;
  private final TradingSessionCloseCoordinator closeCoordinator;

  GatewayAdmissionAutomation(
      GatewayAdmissionGate admissionGate,
      GatewayOperationalPolicy policy,
      GatewayOperationAuditRecorder auditRecorder,
      TradingSessionClosePort tradingSessionClosePort) {
    this.admissionGate = admissionGate;
    this.policy = policy;
    this.auditRecorder = auditRecorder;
    this.closeCoordinator = new TradingSessionCloseCoordinator(tradingSessionClosePort);
  }

  void apply(TradingSystemStatus status, Instant now) {
    if (admissionGate.state() == GatewayAdmissionGate.State.CLOSED) {
      return;
    }
    if (!closeIfDue(status, now)) {
      applyReadinessProtection(status, now);
    }
  }

  void monitor(TradingSystemStatus status, Instant now) {
    if (admissionGate.state() == GatewayAdmissionGate.State.CLOSED) {
      closeCoordinator.request(status, now);
      return;
    }
    apply(status, now);
  }

  boolean closeIfDue(TradingSystemStatus status, Instant now) {
    if (admissionGate.state() == GatewayAdmissionGate.State.CLOSED) {
      return true;
    }
    if (!isSessionCloseDue(now)) {
      return false;
    }
    admissionGate.closeDay();
    auditRecorder.recordAutomatic(
        GatewayOperation.CLOSE_DAY,
        "SESSION_CLOSE_TIME_REACHED",
        admissionGate.state(),
        status,
        now);
    closeCoordinator.request(status, now);
    return true;
  }

  void closeDay(TradingSystemStatus status, Instant now) {
    admissionGate.closeDay();
    closeCoordinator.request(status, now);
  }

  int requiredConsecutiveOpenEligibleChecks() {
    return policy.requiredConsecutiveOpenEligibleChecks();
  }

  private void applyReadinessProtection(TradingSystemStatus status, Instant now) {
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
