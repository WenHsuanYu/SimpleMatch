package com.simplematch.riskservice.cdc;

/** Durable delivery backlog and oldest-undelivered age at one safe refresh point. */
public record CdcDeliverySnapshot(long lagEvents, long oldestUndeliveredAgeMillis) {

  /** Rejects invalid operational measurements. */
  public CdcDeliverySnapshot {
    if (lagEvents < 0 || oldestUndeliveredAgeMillis < 0) {
      throw new IllegalArgumentException("delivery measurements must not be negative");
    }
  }
}
