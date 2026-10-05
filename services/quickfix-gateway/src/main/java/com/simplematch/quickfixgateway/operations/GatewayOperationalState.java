package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Holds the process-local observation history needed for an explicit Gateway open decision. */
final class GatewayOperationalState {
  private TradingSystemObservation latestObservation;
  private int consecutiveOpenEligibleChecks;

  /** Records a report without carrying an expired qualification streak across a reporting gap. */
  TradingSystemStatus report(
      TradingSystemObservation observation, TradingSystemStatusEvaluator evaluator, Instant now) {
    final TradingSystemObservation requiredObservation =
        OperationalStatusValidation.required(observation, "observation");
    final TradingSystemStatus status = evaluator.evaluate(requiredObservation, now);
    if (latestObservation != null
        && !evaluator.evaluate(latestObservation, now).isOpenEligible()) {
      consecutiveOpenEligibleChecks = 0;
    }
    latestObservation = requiredObservation;
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
