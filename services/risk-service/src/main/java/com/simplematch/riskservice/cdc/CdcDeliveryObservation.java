package com.simplematch.riskservice.cdc;

import com.simplematch.config.delivery.DeliveryPosition;
import java.util.Objects;
import java.util.UUID;

/** Exact Risk outbox event observed after publication to Kafka. */
public record CdcDeliveryObservation(
    UUID eventId, DeliveryPosition position, long observedAtUnixMs) {

  /**
   * Validates the durable Kafka observation coordinates.
   *
   * @param eventId exact Risk outbox event identity
   * @param position exact Kafka delivery position
   * @param observedAtUnixMs observation timestamp
   */
  public CdcDeliveryObservation {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(position, "position");
    if (observedAtUnixMs < 0) {
      throw new IllegalArgumentException("observedAtUnixMs must not be negative");
    }
  }
}
