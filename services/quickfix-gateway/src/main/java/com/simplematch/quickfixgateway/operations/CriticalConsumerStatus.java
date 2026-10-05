package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.List;

/** Progress and health of one critical consumer, without full trading identity attestation. */
public record CriticalConsumerStatus(
    CriticalConsumer component,
    OperationalComponentState state,
    List<ConsumerPartitionProgress> partitionProgress,
    Instant observedAt,
    String reason) {
  /** Captures immutable progress facts from one critical consumer adapter. */
  public CriticalConsumerStatus {
    component = OperationalStatusValidation.required(component, "component");
    state = OperationalStatusValidation.required(state, "state");
    partitionProgress =
        List.copyOf(OperationalStatusValidation.required(partitionProgress, "partitionProgress"));
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}
