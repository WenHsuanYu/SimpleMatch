package com.simplematch.accountservice.reservation;

import java.util.Objects;

/**
 * Application command for applying one execution fill to one authoritative reservation.
 *
 * @param reservation the reservation identity that must match the locked authority state
 * @param fill the execution fill to apply idempotently
 */
public record ApplyFillOperation(ReservationIdentity reservation, ExecutionFill fill) {
  /** Requires both domain values so an incomplete command cannot enter the application service. */
  public ApplyFillOperation {
    Objects.requireNonNull(reservation, "reservation");
    Objects.requireNonNull(fill, "fill");
  }
}
