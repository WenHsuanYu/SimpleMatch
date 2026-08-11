package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Holds the process-local observation history needed for an explicit Gateway open decision. */
final class GatewayOperationalState {
  private TradingSystemObservation latestObservation;
  private int consecutiveOpenEligibleChecks;

  TradingSystemStatus report(
      TradingSystemObservation observation, TradingSystemStatusEvaluator evaluator, Instant now) {
    latestObservation = OperationalStatusValidation.required(observation, "observation");
    final TradingSystemStatus status = evaluator.evaluate(latestObservation, now);
    if (status.isOpenEligible()) {
      consecutiveOpenEligibleChecks++;
    } else {
      consecutiveOpenEligibleChecks = 0;
    }
    return status;
  }

  TradingSystemStatus current(TradingSystemStatusEvaluator evaluator, Instant now) {
    if (latestObservation == null) {
      return new TradingSystemStatus(
          TradingReadiness.PAUSE_REQUIRED,
          Optional.empty(),
          List.of("NO_OPERATIONAL_STATUS_OBSERVATION"),
          List.of(),
          now);
    }
    return evaluator.evaluate(latestObservation, now);
  }

  int consecutiveOpenEligibleChecks() {
    return consecutiveOpenEligibleChecks;
  }
}
