package com.simplematch.accountservice.reservation;

/**
 * Creation and latest-update timestamps exposed by a reservation response projection.
 *
 * @param createdAtUnixMs first response timestamp in Unix milliseconds
 * @param updatedAtUnixMs latest response timestamp in Unix milliseconds
 */
public record ReservationResponseTiming(long createdAtUnixMs, long updatedAtUnixMs) {
  /** Requires ordered non-negative response timestamps. */
  public ReservationResponseTiming {
    if (createdAtUnixMs < 0 || updatedAtUnixMs < createdAtUnixMs) {
      throw new IllegalArgumentException("reservation response timestamps are invalid");
    }
  }
}
