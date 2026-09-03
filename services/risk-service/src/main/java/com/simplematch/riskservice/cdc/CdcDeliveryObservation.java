package com.simplematch.riskservice.cdc;

import java.util.Objects;
import java.util.UUID;

/** Exact Risk outbox event observed after publication to Kafka. */
public record CdcDeliveryObservation(
    UUID eventId, String topic, int partition, long offset, long observedAtUnixMs) {

  /** Validates the durable Kafka observation coordinates. */
  public CdcDeliveryObservation {
    Objects.requireNonNull(eventId, "eventId");
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    if (partition < 0 || offset < 0 || observedAtUnixMs < 0) {
      throw new IllegalArgumentException("delivery coordinates must not be negative");
    }
  }
}
