package com.simplematch.quickfixgateway.operations;

import java.time.Instant;

/** Kafka availability, topology, and reported conflict facts without a trading identity. */
public record KafkaStatus(
    OperationalComponentState state,
    int commandPartitionCount,
    int eventPartitionCount,
    boolean sameEventIdDifferentPayload,
    Instant observedAt,
    String reason) {
  /** Validates normalized Kafka topology and integrity facts. */
  public KafkaStatus {
    state = OperationalStatusValidation.required(state, "state");
    commandPartitionCount =
        OperationalStatusValidation.positive(commandPartitionCount, "commandPartitionCount");
    eventPartitionCount =
        OperationalStatusValidation.positive(eventPartitionCount, "eventPartitionCount");
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}
