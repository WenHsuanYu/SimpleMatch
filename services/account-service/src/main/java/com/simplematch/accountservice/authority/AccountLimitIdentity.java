package com.simplematch.accountservice.authority;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Identity of the daily account notional limit.
 *
 * @param accountId account owning the limit
 * @param tradingDay trading day covered by the limit
 * @param currency supported limit currency
 */
public record AccountLimitIdentity(AccountId accountId, LocalDate tradingDay, String currency) {
  /** Requires the account scope, trading day, and supported currency. */
  public AccountLimitIdentity {
    Objects.requireNonNull(accountId, "account_id");
    Objects.requireNonNull(tradingDay, "trading_day");
    if (!"TWD".equals(currency)) {
      throw new IllegalArgumentException("currency must be TWD");
    }
  }
}
