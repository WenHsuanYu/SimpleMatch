package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.ReservationOutcome;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Response state projected from one authoritative reservation lifecycle.
 *
 * @param reservedNotional notional still reported by the lifecycle
 * @param outcome state-specific status and reason
 * @param timing response creation and latest-update timestamps
 */
public record ReservationResponseState(
    BigDecimal reservedNotional, ReservationOutcome outcome, ReservationResponseTiming timing) {
  /** Requires a non-negative notional and complete response state. */
  public ReservationResponseState {
    if (reservedNotional == null || reservedNotional.signum() < 0) {
      throw new IllegalArgumentException("reserved_notional must be non-negative");
    }
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(timing, "timing");
  }
}
