package com.simplematch.riskservice.cdc;

/** Durable delivery backlog and oldest-undelivered age at one safe refresh point. */
public record CdcDeliverySnapshot(long lagEvents, long oldestUndeliveredAgeMillis) {

  /**
   * Rejects invalid operational measurements.
   *
   * @param lagEvents undelivered Risk outbox event count
   * @param oldestUndeliveredAgeMillis age of the oldest undelivered event
   */
  public CdcDeliverySnapshot {
    if (lagEvents < 0 || oldestUndeliveredAgeMillis < 0) {
      throw new IllegalArgumentException("delivery measurements must not be negative");
    }
  }
}
