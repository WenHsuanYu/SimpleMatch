package com.simplematch.accountservice.authority;

import com.simplematch.accountservice.reservation.ReservationTerms.InstrumentSymbol;
import java.util.Objects;

/**
 * Identity of one account's inventory for one instrument.
 *
 * @param accountId account owning the position
 * @param symbol instrument symbol
 */
public record AccountPositionIdentity(AccountId accountId, String symbol) {
  /** Requires a canonical account and the same symbol contract used by reservations. */
  public AccountPositionIdentity {
    Objects.requireNonNull(accountId, "account_id");
    symbol = new InstrumentSymbol(symbol).value();
  }
}
