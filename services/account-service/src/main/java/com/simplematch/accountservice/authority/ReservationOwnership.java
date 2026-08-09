package com.simplematch.accountservice.authority;

import java.util.Objects;

/**
 * Account ownership of one reservation.
 *
 * @param accountId owning canonical account identifier
 */
public record ReservationOwnership(AccountId accountId) {
  /** Requires a canonical account identifier. */
  public ReservationOwnership {
    Objects.requireNonNull(accountId, "account_id");
  }
}
