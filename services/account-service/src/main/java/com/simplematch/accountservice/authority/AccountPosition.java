package com.simplematch.accountservice.authority;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable account inventory composed from identity, inventory, and revision state.
 *
 * @param identity account and instrument identity
 * @param inventory long/short inventory state
 * @param revision optimistic version and update timestamp
 */
public record AccountPosition(
    AccountPositionIdentity identity,
    AccountPositionInventory inventory,
    AccountPositionRevision revision) {
  /** Requires the three independent semantic parts of an account position. */
  public AccountPosition {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(revision, "revision");
  }

  /** Returns an empty position for controlled account administration. */
  public static AccountPosition provisioned(String accountId, String symbol, long now) {
    return new AccountPosition(
        new AccountPositionIdentity(accountId, symbol),
        new AccountPositionInventory(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        AccountPositionRevision.initial(now));
  }

  /** Returns a copy with a new inventory and optimistic revision. */
  public AccountPosition withInventory(
      AccountPositionInventory nextInventory, AccountPositionRevision nextRevision) {
    return new AccountPosition(identity, nextInventory, nextRevision);
  }

  /** Returns the account identifier for boundary projections. */
  public String accountId() {
    return identity.accountId();
  }

  /** Returns the instrument symbol for boundary projections. */
  public String symbol() {
    return identity.symbol();
  }

  /** Returns long inventory. */
  public BigDecimal longQuantity() {
    return inventory.longQuantity();
  }

  /** Returns short inventory. */
  public BigDecimal shortQuantity() {
    return inventory.shortQuantity();
  }

  /** Returns reserved long inventory. */
  public BigDecimal reservedLongQuantity() {
    return inventory.reservedLongQuantity();
  }

  /** Returns reserved short inventory. */
  public BigDecimal reservedShortQuantity() {
    return inventory.reservedShortQuantity();
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
