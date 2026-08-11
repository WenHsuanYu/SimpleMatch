package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure policy that converts normalized technical facts into a Gateway-domain admission decision.
 *
 * <p>Infrastructure adapters are responsible for collecting Kubernetes, Kafka, and service status.
 * This facade composes focused readiness checks over stable domain values and has no transport-side
 * effects.
 */
public final class TradingSystemStatusEvaluator {
  private final TradingSystemComponentAssessment componentAssessment;
  private final MatchingFleetReadinessAssessment matchingFleetAssessment;
  private final CriticalConsumerReadinessAssessment criticalConsumerAssessment;

  /** Creates the Phase 1 fixed-fleet readiness policy. */
  public TradingSystemStatusEvaluator(
      int expectedPartitionCount,
      Duration staleStatusAfter,
      Duration warningOldestEventAfter,
      Duration pauseOldestEventAfter) {
    final TradingSystemReadinessThresholds thresholds =
        new TradingSystemReadinessThresholds(
            expectedPartitionCount,
            staleStatusAfter,
            warningOldestEventAfter,
            pauseOldestEventAfter);
    componentAssessment = new TradingSystemComponentAssessment(thresholds);
    matchingFleetAssessment = new MatchingFleetReadinessAssessment(thresholds, componentAssessment);
    criticalConsumerAssessment =
        new CriticalConsumerReadinessAssessment(thresholds, componentAssessment);
  }

  /** Evaluates one complete status observation at a caller-supplied time. */
  public TradingSystemStatus evaluate(TradingSystemObservation observation, Instant now) {
    final TradingSystemObservation requiredObservation =
        OperationalStatusValidation.required(observation, "observation");
    final TradingSystemAssessment assessment =
        new TradingSystemAssessment(
            requiredObservation.riskStatus().identity(),
            OperationalStatusValidation.required(now, "now"));

    componentAssessment.assessRisk(requiredObservation.riskStatus(), assessment);
    matchingFleetAssessment.assess(requiredObservation.matchingFleet(), assessment);
    criticalConsumerAssessment.assess(requiredObservation.criticalConsumers(), assessment);
    componentAssessment.assessKafka(requiredObservation.kafkaStatus(), assessment);
    return assessment.toStatus();
  }
}
