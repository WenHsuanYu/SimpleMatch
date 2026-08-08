package com.simplematch.accountservice.reservation;

import java.util.Objects;

/**
 * Application command for releasing the remaining authority of one reservation.
 *
 * @param reservation the typed reservation identity that must match authoritative state
 * @param reason the stable release reason supplied by the lifecycle caller
 * @param sourceEventId optional inbound event identity used for inbox deduplication
 */
public record ReleaseReservationOperation(
    ReservationIdentity reservation, ReleaseReason reason, String sourceEventId) {
  /** Creates a synchronous release command without an inbound event identity. */
  public ReleaseReservationOperation(ReservationIdentity reservation, ReleaseReason reason) {
    this(reservation, reason, null);
  }

  /** Requires the identity and an explicit release reason value. */
  public ReleaseReservationOperation {
    Objects.requireNonNull(reservation, "reservation");
    Objects.requireNonNull(reason, "reason");
    if (sourceEventId != null && sourceEventId.isBlank()) {
      throw new IllegalArgumentException("source event id must not be blank");
    }
  }

  /** Stable machine-readable release reason. */
  public record ReleaseReason(String value) {
    /** Normalizes an omitted reason to the explicit empty representation. */
    public ReleaseReason {
      value = Objects.requireNonNullElse(value, "");
    }
  }
}
