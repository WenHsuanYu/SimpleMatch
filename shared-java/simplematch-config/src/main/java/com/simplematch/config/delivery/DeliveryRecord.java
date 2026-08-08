package com.simplematch.config.delivery;

import java.util.Objects;

/** Immutable delivery input retained for retry and quarantine evidence. */
public final class DeliveryRecord {
  private final String eventId;
  private final DeliveryPosition position;
  private final byte[] payload;

  /** Validates and defensively owns the delivery payload. */
  public DeliveryRecord(String eventId, DeliveryPosition position, byte[] payload) {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("delivery event id must not be blank");
    }
    Objects.requireNonNull(position, "delivery position");
    this.eventId = eventId;
    this.position = position;
    this.payload = Objects.requireNonNull(payload, "delivery payload").clone();
  }

  /** Returns the event identity carried by the record. */
  public String eventId() {
    return eventId;
  }

  /** Returns the Kafka position carried by the record. */
  public DeliveryPosition position() {
    return position;
  }

  /** Returns a defensive copy of the delivery payload. */
  public byte[] payload() {
    return payload.clone();
  }
}
