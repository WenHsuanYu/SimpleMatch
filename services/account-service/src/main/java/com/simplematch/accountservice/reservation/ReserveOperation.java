package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountId;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Application command for reserving account authority for one order.
 *
 * <p>The command is composed from request identity and reservation terms rather than a flat list of
 * strings and decimals. Each child value owns its invariant and same-shaped fields have different
 * Java types.
 *
 * @param identity the request, order, and account identity
 * @param terms the instrument, side, quantity, and optional limit price
 */
public record ReserveOperation(ReservationRequestIdentity identity, ReservationTerms terms) {
  /** Requires a complete reserve operation. */
  public ReserveOperation {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(terms, "terms");
  }

  /** Returns the wire-compatible request identifier. */
  public String requestId() {
    return identity.requestId().value();
  }

  /** Returns the Account reservation identity represented by this reserve command. */
  public ReservationIdentity reservationIdentity() {
    return new ReservationIdentity(
        new ReservationIdentity.RequestId(requestId()),
        new ReservationIdentity.ReservationId(orderId()),
        new ReservationIdentity.OrderId(orderId()));
  }

  /** Returns the wire-compatible order identifier. */
  public String orderId() {
    return identity.orderId().value();
  }

  /** Returns the canonical Account-domain identity. */
  public AccountId accountIdentity() {
    return identity.accountId().canonical();
  }

  /** Returns the wire-compatible account identifier. */
  public String accountId() {
    return identity.accountId().value();
  }

  /** Returns the instrument symbol. */
  public String symbol() {
    return terms.symbol().value();
  }

  /** Returns the venue MIC that qualifies the reserved instrument. */
  public String venueMic() {
    return terms.venueMic().value();
  }

  /** Returns the order side. */
  public Side side() {
    return terms.side();
  }

  /** Returns the positive quantity to reserve. */
  public BigDecimal quantity() {
    return terms.quantity().value();
  }

  /** Returns the optional limit price. */
  public BigDecimal limitPrice() {
    return terms.limitPrice().value();
  }
}
