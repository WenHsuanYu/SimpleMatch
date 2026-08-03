package com.simplematch.accountservice.reservation;

import java.util.Objects;

/**
 * Application command for releasing the remaining authority of one reservation.
 *
 * @param reservation the typed reservation identity that must match authoritative state
 * @param reason the stable release reason supplied by the lifecycle caller
 */
public record ReleaseReservationOperation(ReservationIdentity reservation, ReleaseReason reason) {
  /** Requires the identity and an explicit release reason value. */
  public ReleaseReservationOperation {
    Objects.requireNonNull(reservation, "reservation");
    Objects.requireNonNull(reason, "reason");
  }

  /** Stable machine-readable release reason. */
  public record ReleaseReason(String value) {
    /** Normalizes an omitted reason to the explicit empty representation. */
    public ReleaseReason {
      value = Objects.requireNonNullElse(value, "");
    }
  }
}
