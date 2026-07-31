package com.simplematch.accountservice.authority;

/**
 * Account ownership of one reservation.
 *
 * @param accountId owning account identifier
 */
public record ReservationOwnership(String accountId) {
  /** Requires a nonblank account identifier. */
  public ReservationOwnership {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("account_id must not be blank");
    }
  }
}
