package com.simplematch.accountservice.authority;

/** Account-owned lifecycle state, mapped explicitly to events at the transport boundary. */
public enum ReservationStatus {
  /** Authority is held and unfilled quantity remains. */
  RESERVATION_STATUS_ACCEPTED,
  /** The request was rejected without holding authority. */
  RESERVATION_STATUS_REJECTED,
  /** Unused authority was released after cancellation or expiry. */
  RESERVATION_STATUS_RELEASED,
  /** All requested quantity has been filled. */
  RESERVATION_STATUS_APPLIED
}
