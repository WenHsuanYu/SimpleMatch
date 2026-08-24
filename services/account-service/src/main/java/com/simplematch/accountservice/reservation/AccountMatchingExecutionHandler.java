package com.simplematch.accountservice.reservation;

/** Applies one Account-owned matching effect through the reservation transaction seam. */
@FunctionalInterface
public interface AccountMatchingExecutionHandler {
  /** Applies one translated matching effect to authoritative Account state. */
  ReservationRecord apply(MatchingAccountEffect effect);
}
