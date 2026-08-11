package com.simplematch.accountservice.reservation;

/** Indicates that an idempotency key was reused for different reservation facts. */
public final class ReservationRequestConflictException extends IllegalArgumentException {
  /** Creates a conflict for one request identity. */
  public ReservationRequestConflictException(String requestId) {
    super("reservation request already exists with different facts: " + requestId);
  }
}
