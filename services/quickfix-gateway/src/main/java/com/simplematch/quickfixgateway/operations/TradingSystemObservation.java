package com.simplematch.quickfixgateway.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Complete normalized observation supplied to the Gateway operational domain. */
public record TradingSystemObservation(
    @NotNull @Valid RiskStatus riskStatus,
    @NotNull @Valid MatchingFleetStatus matchingFleet,
    @NotEmpty List<@NotNull @Valid CriticalConsumerStatus> criticalConsumers,
    @NotNull @Valid KafkaStatus kafkaStatus) {
  /** Captures all critical facts needed to decide operational admission. */
  public TradingSystemObservation {
    riskStatus = OperationalStatusValidation.required(riskStatus, "riskStatus");
    matchingFleet = OperationalStatusValidation.required(matchingFleet, "matchingFleet");
    criticalConsumers =
        List.copyOf(OperationalStatusValidation.required(criticalConsumers, "criticalConsumers"));
    kafkaStatus = OperationalStatusValidation.required(kafkaStatus, "kafkaStatus");
  }
}
