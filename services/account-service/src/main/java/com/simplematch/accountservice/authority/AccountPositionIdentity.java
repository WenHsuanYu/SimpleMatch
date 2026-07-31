package com.simplematch.accountservice.authority;

/**
 * Identity of one account's inventory for one instrument.
 *
 * @param accountId account owning the position
 * @param symbol instrument symbol
 */
public record AccountPositionIdentity(String accountId, String symbol) {
  /** Requires a nonblank account and instrument identity. */
  public AccountPositionIdentity {
    accountId = requireText(accountId, "account_id");
    symbol = requireText(symbol, "symbol");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
