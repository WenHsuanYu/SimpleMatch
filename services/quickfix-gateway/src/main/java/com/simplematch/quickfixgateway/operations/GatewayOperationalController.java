package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Clock;
import java.time.Instant;

/**
 * Application authority for one Gateway's trading-day admission operations.
 *
 * <p>It deliberately accepts only {@link TradingSystemObservation}; infrastructure adapters own
 * Kubernetes, Kafka, and service-specific client calls. A safe recovery never opens the gate by
 * itself: only {@link #open(String, String)} may do that after fresh ready checks.
 *
 * <p>The admission gate is process-local state and the audit store is durable evidence rather than
 * authoritative trading state, so this controller does not create a database transaction around
 * operational decisions or remote Risk calls.
 */
public class GatewayOperationalController {
  private final GatewayAdmissionGate admissionGate;
  private final TradingSystemStatusEvaluator statusEvaluator;
  private final GatewayOperationalState operationalState;
  private final GatewayAdmissionAutomation automation;
  private final GatewayOperationAuditRecorder auditRecorder;
  private final Clock clock;

  /** Creates the single-process Gateway operational authority. */
  public GatewayOperationalController(
      GatewayAdmissionGate admissionGate,
      TradingSystemStatusEvaluator statusEvaluator,
      GatewayOperationalPolicy policy,
      GatewayOperationAuditStore auditStore,
      TradingSessionClosePort tradingSessionClosePort,
      Clock clock) {
    this.admissionGate = admissionGate;
    this.statusEvaluator = statusEvaluator;
    this.operationalState = new GatewayOperationalState();
    this.auditRecorder = new GatewayOperationAuditRecorder(auditStore);
    this.automation =
        new GatewayAdmissionAutomation(
            admissionGate, policy, auditRecorder, tradingSessionClosePort);
    this.clock = clock;
  }

  /**
   * Records one fresh adapter observation and applies any required automatic safety action.
   *
   * @return the domain readiness decision produced from this fresh observation
   */
  public synchronized TradingSystemStatus report(TradingSystemObservation observation) {
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.report(observation, statusEvaluator, now);
    automation.apply(status, now);
    return status;
  }

  /**
   * Reassesses the latest observation for staleness, session close, and pending close retry.
   *
   * @return the current domain readiness decision after automatic protection is applied
   */
  public synchronized TradingSystemStatus monitor() {
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.current(statusEvaluator, now);
    automation.monitor(status, now);
    return status;
  }

  /** Returns the current readiness status without creating side effects or an audit record. */
  public synchronized GatewayOperationResult status() {
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.current(statusEvaluator, now);
    return new GatewayOperationResult(
        GatewayOperation.STATUS, true, admissionGate.state(), "STATUS", status, now);
  }

  /** Attempts an explicit operator open after the configured number of fresh ready checks. */
  public synchronized GatewayOperationResult open(String actor, String reason) {
    final GatewayOperationalCommand command =
        new GatewayOperationalCommand(GatewayOperation.OPEN, actor, reason);
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.current(statusEvaluator, now);
    automation.apply(status, now);
    final boolean accepted =
        status.isOpenEligible()
            && operationalState.consecutiveOpenEligibleChecks()
                >= automation.requiredConsecutiveOpenEligibleChecks()
            && admissionGate.open();
    final String outcomeReason =
        accepted
            ? "OPENED"
            : GatewayOpenRejections.reason(
                admissionGate.state(),
                status,
                operationalState.consecutiveOpenEligibleChecks(),
                automation.requiredConsecutiveOpenEligibleChecks());
    auditRecorder.record(command, accepted, admissionGate.state(), status, now);
    return new GatewayOperationResult(
        GatewayOperation.OPEN, accepted, admissionGate.state(), outcomeReason, status, now);
  }

  /** Pauses only new orders while retaining cancellation admission in an active session. */
  public synchronized GatewayOperationResult pauseNewOrders(String actor, String reason) {
    final GatewayOperationalCommand command =
        new GatewayOperationalCommand(GatewayOperation.PAUSE_NEW_ORDERS, actor, reason);
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.current(statusEvaluator, now);
    final GatewayAdmissionGate.State before = admissionGate.state();
    if (!automation.closeIfDue(status, now)) {
      admissionGate.pauseNewOrders(command.reason());
    }
    final boolean accepted = admissionGate.state() == GatewayAdmissionGate.State.NEW_ORDERS_PAUSED;
    auditRecorder.record(command, accepted, admissionGate.state(), status, now);
    return new GatewayOperationResult(
        GatewayOperation.PAUSE_NEW_ORDERS,
        accepted,
        admissionGate.state(),
        accepted ? "NEW_ORDERS_PAUSED" : before.name(),
        status,
        now);
  }

  /**
   * Interrupts new-order and cancellation admission because an operator has found an integrity
   * risk.
   */
  public synchronized GatewayOperationResult interruptMarket(String actor, String reason) {
    final GatewayOperationalCommand command =
        new GatewayOperationalCommand(GatewayOperation.INTERRUPT_MARKET, actor, reason);
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.current(statusEvaluator, now);
    if (!automation.closeIfDue(status, now)) {
      admissionGate.interruptMarket();
    }
    final boolean accepted = admissionGate.state() == GatewayAdmissionGate.State.MARKET_INTERRUPTED;
    auditRecorder.record(command, accepted, admissionGate.state(), status, now);
    return new GatewayOperationResult(
        GatewayOperation.INTERRUPT_MARKET,
        accepted,
        admissionGate.state(),
        accepted ? "MARKET_INTERRUPTED" : admissionGate.state().name(),
        status,
        now);
  }

  /** Closes admission and starts the idempotent Risk-owned trading-session close workflow. */
  public synchronized GatewayOperationResult closeDay(String actor, String reason) {
    final GatewayOperationalCommand command =
        new GatewayOperationalCommand(GatewayOperation.CLOSE_DAY, actor, reason);
    final Instant now = clock.instant();
    final TradingSystemStatus status = operationalState.current(statusEvaluator, now);
    automation.closeDay(status, now);
    auditRecorder.record(command, true, admissionGate.state(), status, now);
    return new GatewayOperationResult(
        GatewayOperation.CLOSE_DAY, true, admissionGate.state(), "MARKET_CLOSED", status, now);
  }
}
