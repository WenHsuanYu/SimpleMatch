package com.simplematch.accountservice.authority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable daily account notional limit composed from identity, ledger, and revision state.
 *
 * @param identity daily account-limit identity
 * @param ledger notional balance state
 * @param revision optimistic version and update timestamp
 */
public record AccountLimit(
    AccountLimitIdentity identity, AccountLimitLedger ledger, AccountLimitRevision revision) {
  /** Requires the three independent semantic parts of an account limit. */
  public AccountLimit {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(revision, "revision");
  }

  /** Returns a provisioned TWD limit with no reservations or utilization. */
  public static AccountLimit provisioned(
      AccountId accountId, LocalDate tradingDay, BigDecimal limitTotalNotional, long now) {
    return new AccountLimit(
        new AccountLimitIdentity(accountId, tradingDay, "TWD"),
        new AccountLimitLedger(
            limitTotalNotional, BigDecimal.ZERO, BigDecimal.ZERO, limitTotalNotional),
        AccountLimitRevision.initial(now));
  }

  /** Returns a copy with a new ledger and optimistic revision. */
  public AccountLimit withLedger(AccountLimitLedger nextLedger, AccountLimitRevision nextRevision) {
    return new AccountLimit(identity, nextLedger, nextRevision);
  }

  /** Returns the canonical Account-domain identity. */
  public AccountId accountIdentity() {
    return identity.accountId();
  }

  /** Returns the account identifier for boundary projections. */
  public String accountId() {
    return accountIdentity().wireValue();
  }

  /** Returns the trading day for boundary projections. */
  public LocalDate tradingDay() {
    return identity.tradingDay();
  }

  /** Returns the persisted currency code. */
  public String currency() {
    return identity.currency();
  }

  /** Returns the total daily notional authority. */
  public BigDecimal limitTotalNotional() {
    return ledger.limitTotalNotional();
  }

  /** Returns the notional currently held by reservations. */
  public BigDecimal reservedNotional() {
    return ledger.reservedNotional();
  }

  /** Returns the notional already consumed by fills. */
  public BigDecimal utilizedNotional() {
    return ledger.utilizedNotional();
  }

  /** Returns the remaining available notional. */
  public BigDecimal availableNotional() {
    return ledger.availableNotional();
  }

  /** Returns the optimistic version. */
  public long version() {
    return revision.version();
  }

  /** Returns the last update timestamp. */
  public long updatedAtUnixMs() {
    return revision.updatedAtUnixMs();
  }
}
