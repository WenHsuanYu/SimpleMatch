package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.List;

/** Complete status of one critical final Matching Event consumer group. */
public record CriticalConsumerStatus(
    CriticalConsumer component,
    OperationalComponentState state,
    TradingIdentity identity,
    List<ConsumerPartitionProgress> partitionProgress,
    Instant observedAt,
    String reason) {
  /** Captures immutable progress facts from one critical consumer adapter. */
  public CriticalConsumerStatus {
    component = OperationalStatusValidation.required(component, "component");
    state = OperationalStatusValidation.required(state, "state");
    identity = OperationalStatusValidation.required(identity, "identity");
    partitionProgress =
        List.copyOf(OperationalStatusValidation.required(partitionProgress, "partitionProgress"));
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}
