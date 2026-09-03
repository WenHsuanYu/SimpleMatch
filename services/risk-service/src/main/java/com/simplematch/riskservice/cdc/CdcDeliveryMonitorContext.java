package com.simplematch.riskservice.cdc;

import java.time.Clock;
import java.util.Objects;

/** Identifies one CDC metric target and its durable refresh clock. */
public record CdcDeliveryMonitorContext(String metricName, String topic, Clock clock) {

  /** Validates the target identity and time source used by one CDC monitor. */
  public CdcDeliveryMonitorContext {
    if (metricName == null || metricName.isBlank()) {
      throw new IllegalArgumentException("metricName must not be blank");
    }
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    Objects.requireNonNull(clock, "clock");
  }
}
