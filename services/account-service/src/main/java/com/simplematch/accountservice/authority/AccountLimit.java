package com.simplematch.accountservice.authority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable account notional limit and its optimistic version.
 */
public record AccountLimit(
        String accountId,
        LocalDate tradingDay,
        String currency,
        BigDecimal limitTotalNotional,
        BigDecimal reservedNotional,
        BigDecimal utilizedNotional,
        BigDecimal availableNotional,
        long version,
        long updatedAtUnixMs) {
    /**
     * Validates the persisted limit invariant.
     */
    public AccountLimit {
        accountId = text(accountId, "account_id");
        Objects.requireNonNull(tradingDay, "trading_day");
        if (!"TWD".equals(currency)) {
            throw new IllegalArgumentException("currency must be TWD");
        }
        limitTotalNotional = nonNegative(limitTotalNotional, "limit_total_notional");
        reservedNotional = nonNegative(reservedNotional, "reserved_notional");
        utilizedNotional = nonNegative(utilizedNotional, "utilized_notional");
        availableNotional = nonNegative(availableNotional, "available_notional");
        if (availableNotional.compareTo(limitTotalNotional.subtract(reservedNotional).subtract(utilizedNotional)) != 0) {
            throw new IllegalArgumentException("available_notional must equal limit minus reserved and utilized");
        }
        if (version < 0 || updatedAtUnixMs < 0) {
            throw new IllegalArgumentException("version and timestamp must be non-negative");
        }
    }

    /**
     * Returns a provisioned limit with no reservations or utilization.
     */
    public static AccountLimit provisioned(
            String accountId, LocalDate tradingDay, BigDecimal limitTotalNotional, long now) {
        return new AccountLimit(accountId, tradingDay, "TWD", limitTotalNotional,
                BigDecimal.ZERO, BigDecimal.ZERO, limitTotalNotional, 0, now);
    }

    /**
     * Returns a copy with a new authoritative balance.
     */
    public AccountLimit withBalances(
            BigDecimal reserved, BigDecimal utilized, BigDecimal available, long nextVersion, long now) {
        return new AccountLimit(accountId, tradingDay, currency, limitTotalNotional,
                reserved, utilized, available, nextVersion, now);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }
}
