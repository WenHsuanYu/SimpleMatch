package com.simplematch.quickfixgateway.operations;

import java.time.Instant;

/** Gateway-domain facts about the fixed command and final-event Kafka topology. */
public record KafkaStatus(
    OperationalComponentState state,
    TradingIdentity identity,
    int commandPartitionCount,
    int eventPartitionCount,
    boolean sameEventIdDifferentPayload,
    Instant observedAt,
    String reason) {
  /** Validates normalized Kafka topology and integrity facts. */
  public KafkaStatus {
    state = OperationalStatusValidation.required(state, "state");
    identity = OperationalStatusValidation.required(identity, "identity");
    commandPartitionCount =
        OperationalStatusValidation.positive(commandPartitionCount, "commandPartitionCount");
    eventPartitionCount =
        OperationalStatusValidation.positive(eventPartitionCount, "eventPartitionCount");
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}
