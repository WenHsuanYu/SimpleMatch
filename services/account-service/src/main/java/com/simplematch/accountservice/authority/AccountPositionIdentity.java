package com.simplematch.accountservice.authority;

import java.util.Objects;

/**
 * Identity of one account's inventory for one instrument.
 *
 * @param accountId account owning the position
 * @param symbol instrument symbol
 */
public record AccountPositionIdentity(AccountId accountId, String symbol) {
  /** Requires a canonical account and nonblank instrument identity. */
  public AccountPositionIdentity {
    Objects.requireNonNull(accountId, "account_id");
    symbol = requireText(symbol, "symbol");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
