package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.log4j.Log4j2;

/**
 * Coordinates one process-local request for Risk-owned trading-session closure.
 *
 * <p>The first usable trading-session identity is pinned for the lifetime of the close request.
 * Temporary transport failures are retried on a bounded schedule. Permanent failures stop retries
 * while Gateway admission remains fail-closed.
 */
@Log4j2
final class TradingSessionCloseCoordinator {
  private static final int MAX_ATTEMPTS = 5;
  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

  private final TradingSessionClosePort closePort;
  private String tradingSessionId;
  private int attempts;
  private Instant nextAttemptAt = Instant.MIN;
  private boolean complete;
  private boolean failed;
  private boolean retryFailureReported;

  TradingSessionCloseCoordinator(TradingSessionClosePort closePort) {
    this.closePort =
        OperationalStatusValidation.required(closePort, "tradingSessionClosePort");
  }

  void request(TradingSystemStatus status, Instant now) {
    pinTradingSession(status);
    if (!canAttempt(now)) {
      return;
    }
    performAttempt(now);
  }

  private void pinTradingSession(TradingSystemStatus status) {
    if (tradingSessionId == null && status.identity().isPresent()) {
      tradingSessionId = status.identity().orElseThrow().tradingSessionId();
    }
  }

  private boolean canAttempt(Instant now) {
    return tradingSessionId != null && !complete && !failed && !now.isBefore(nextAttemptAt);
  }

  private void performAttempt(Instant now) {
    attempts++;
    try {
      closePort.close(tradingSessionId);
      recordAcceptedClose();
    } catch (RetryableTradingSessionCloseException retryableFailure) {
      recordRetryableFailure(now, retryableFailure);
    } catch (RuntimeException permanentFailure) {
      recordPermanentFailure(permanentFailure);
    }
  }

  private void recordAcceptedClose() {
    complete = true;
    if (retryFailureReported) {
      log.info(
          "Risk accepted trading-session close after retry: tradingSessionId={}, attempts={}",
          tradingSessionId,
          attempts);
    }
  }

  private void recordRetryableFailure(
      Instant now, RetryableTradingSessionCloseException retryableFailure) {
    reportInitialRetryableFailure(retryableFailure);
    if (attempts >= MAX_ATTEMPTS) {
      failed = true;
      log.error(
          "Risk trading-session close exhausted bounded retries: "
              + "tradingSessionId={}, attempts={}",
          tradingSessionId,
          attempts,
          retryableFailure);
      return;
    }
    nextAttemptAt = now.plus(RETRY_INTERVAL);
  }

  private void reportInitialRetryableFailure(
      RetryableTradingSessionCloseException retryableFailure) {
    if (retryFailureReported) {
      return;
    }
    log.warn(
        "Risk trading-session close is pending; Gateway admission remains closed: "
            + "tradingSessionId={}, attempt={}",
        tradingSessionId,
        attempts,
        retryableFailure);
    retryFailureReported = true;
  }

  private void recordPermanentFailure(RuntimeException permanentFailure) {
    failed = true;
    log.error(
        "Risk trading-session close failed permanently; Gateway admission remains closed: "
            + "tradingSessionId={}",
        tradingSessionId,
        permanentFailure);
  }
}
