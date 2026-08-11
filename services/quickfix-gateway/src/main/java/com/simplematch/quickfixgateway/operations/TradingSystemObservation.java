package com.simplematch.quickfixgateway.operations;

import java.util.List;

/** Complete normalized observation supplied to the Gateway operational domain. */
public record TradingSystemObservation(
    RiskStatus riskStatus,
    MatchingFleetStatus matchingFleet,
    List<CriticalConsumerStatus> criticalConsumers,
    KafkaStatus kafkaStatus) {
  /** Captures all critical facts needed to decide operational admission. */
  public TradingSystemObservation {
    riskStatus = OperationalStatusValidation.required(riskStatus, "riskStatus");
    matchingFleet = OperationalStatusValidation.required(matchingFleet, "matchingFleet");
    criticalConsumers =
        List.copyOf(OperationalStatusValidation.required(criticalConsumers, "criticalConsumers"));
    kafkaStatus = OperationalStatusValidation.required(kafkaStatus, "kafkaStatus");
  }
}
