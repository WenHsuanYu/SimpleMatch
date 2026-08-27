package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Instant;
import java.time.ZonedDateTime;
import lombok.extern.log4j.Log4j2;

/** Applies automatic session-close and readiness protections to the Gateway admission gate. */
@Log4j2
final class GatewayAdmissionAutomation {
  private final GatewayAdmissionGate admissionGate;
  private final GatewayOperationalPolicy policy;
  private final GatewayOperationAuditRecorder auditRecorder;
  private final TradingSessionClosePort tradingSessionClosePort;
  private boolean closeAccepted;
  private boolean closeFailureReported;

  GatewayAdmissionAutomation(
      GatewayAdmissionGate admissionGate,
      GatewayOperationalPolicy policy,
      GatewayOperationAuditRecorder auditRecorder,
      TradingSessionClosePort tradingSessionClosePort) {
    this.admissionGate = admissionGate;
    this.policy = policy;
    this.auditRecorder = auditRecorder;
    this.tradingSessionClosePort = tradingSessionClosePort;
  }

  void apply(TradingSystemStatus status, Instant now) {
    if (admissionGate.state() == GatewayAdmissionGate.State.CLOSED) {
      requestTradingSessionClose(status);
      return;
    }
    if (!closeIfDue(status, now)) {
      applyReadinessProtection(status, now);
    }
  }

  boolean closeIfDue(TradingSystemStatus status, Instant now) {
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
    requestTradingSessionClose(status);
    return true;
  }

  void closeDay(TradingSystemStatus status) {
    admissionGate.closeDay();
    requestTradingSessionClose(status);
  }

  int requiredConsecutiveOpenEligibleChecks() {
    return policy.requiredConsecutiveOpenEligibleChecks();
  }

  private void requestTradingSessionClose(TradingSystemStatus status) {
    if (closeAccepted || status.identity().isEmpty()) {
      return;
    }
    final String tradingSessionId = status.identity().orElseThrow().tradingSessionId();
    try {
      tradingSessionClosePort.close(tradingSessionId);
      closeAccepted = true;
      if (closeFailureReported) {
        log.info(
            "Risk accepted trading-session close after retry: tradingSessionId={}",
            tradingSessionId);
      }
    } catch (RuntimeException temporarilyUnavailable) {
      if (!closeFailureReported) {
        log.warn(
            "Risk trading-session close is pending; Gateway admission remains closed: "
                + "tradingSessionId={}",
            tradingSessionId,
            temporarilyUnavailable);
        closeFailureReported = true;
      }
    }
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
