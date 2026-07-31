package com.simplematch.accountservice.authority;

import java.math.BigDecimal;

/**
 * Daily notional ledger owned by one account limit aggregate.
 *
 * @param limitTotalNotional configured notional limit
 * @param reservedNotional notional currently reserved
 * @param utilizedNotional notional already utilized
 * @param availableNotional notional remaining after reservations and utilization
 */
public record AccountLimitLedger(
    BigDecimal limitTotalNotional,
    BigDecimal reservedNotional,
    BigDecimal utilizedNotional,
    BigDecimal availableNotional) {
  /** Enforces non-negative amounts and the account-limit balance equation. */
  public AccountLimitLedger {
    limitTotalNotional = nonNegative(limitTotalNotional, "limit_total_notional");
    reservedNotional = nonNegative(reservedNotional, "reserved_notional");
    utilizedNotional = nonNegative(utilizedNotional, "utilized_notional");
    availableNotional = nonNegative(availableNotional, "available_notional");
    if (availableNotional.compareTo(
            limitTotalNotional.subtract(reservedNotional).subtract(utilizedNotional))
        != 0) {
      throw new IllegalArgumentException(
          "available_notional must equal limit minus reserved and utilized");
    }
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}
