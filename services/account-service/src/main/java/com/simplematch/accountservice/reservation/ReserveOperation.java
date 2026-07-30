package com.simplematch.accountservice.reservation;

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
    identity = Objects.requireNonNull(identity, "identity");
    terms = Objects.requireNonNull(terms, "terms");
  }

  /**
   * @deprecated Use the domain-value constructor; retained while callers migrate from the flat
   *     transport-shaped signature.
   */
  @Deprecated(forRemoval = false)
  @SuppressWarnings({"PMD.ExcessiveParameterList", "checkstyle:ParameterNumber"})
  public ReserveOperation(
      String requestId,
      String orderId,
      String accountId,
      String symbol,
      Side side,
      BigDecimal quantity,
      BigDecimal limitPrice) {
    this(
        new ReservationRequestIdentity(
            new ReservationRequestIdentity.RequestId(requestId),
            new ReservationRequestIdentity.OrderId(orderId),
            new ReservationRequestIdentity.AccountId(accountId)),
        new ReservationTerms(
            new ReservationTerms.InstrumentSymbol(symbol),
            side,
            new ReservationTerms.ReservationQuantity(quantity),
            new ReservationTerms.LimitPrice(limitPrice)));
  }

  /** Returns the wire-compatible request identifier. */
  public String requestId() {
    return identity.requestId().value();
  }

  /** Returns the wire-compatible order identifier. */
  public String orderId() {
    return identity.orderId().value();
  }

  /** Returns the wire-compatible account identifier. */
  public String accountId() {
    return identity.accountId().value();
  }

  /** Returns the instrument symbol. */
  public String symbol() {
    return terms.symbol().value();
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
