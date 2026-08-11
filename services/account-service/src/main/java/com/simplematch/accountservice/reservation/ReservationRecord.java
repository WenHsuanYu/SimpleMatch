package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.authority.ReservationOwnership;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Read-only response projection of an authoritative account reservation.
 *
 * <p>This type is created from {@link AccountReservation} for gRPC responses. It is not a second
 * aggregate and is never used as a persistence row or write command.
 *
 * @param identity reservation, request, and order identity
 * @param ownership account ownership
 * @param terms immutable reservation terms
 * @param state response state and timestamps
 */
public record ReservationRecord(
    ReservationIdentity identity,
    ReservationOwnership ownership,
    ReservationTerms terms,
    ReservationResponseState state) {
  /** Requires complete semantic response values. */
  public ReservationRecord {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(ownership, "ownership");
    Objects.requireNonNull(terms, "terms");
    Objects.requireNonNull(state, "state");
  }

  /**
   * Projects an authoritative reservation without introducing a persistence-shaped carrier.
   *
   * @param reservation authoritative reservation returned by the account application service
   * @return semantic response projection
   */
  public static ReservationRecord from(AccountReservation reservation) {
    Objects.requireNonNull(reservation, "reservation");
    return new ReservationRecord(
        reservation.identity(),
        reservation.ownership(),
        reservation.terms(),
        new ReservationResponseState(
            reservation.reservedNotional(),
            reservation.lifecycle().outcome(),
            new ReservationResponseTiming(
                reservation.createdAtUnixMs(), reservation.updatedAtUnixMs())));
  }

  /** Returns the reservation identifier for the response boundary. */
  public String reservationId() {
    return identity.reservationId().value();
  }

  /** Returns the idempotent request identifier for the response boundary. */
  public String requestId() {
    return identity.requestId().value();
  }

  /** Returns the owning order identifier. */
  public String orderId() {
    return identity.orderId().value();
  }

  /** Returns the owning account identifier. */
  public String accountId() {
    return ownership.accountId().wireValue();
  }

  /** Returns the reserved instrument symbol. */
  public String symbol() {
    return terms.symbol().value();
  }

  /** Returns the venue MIC that qualifies the reserved instrument. */
  public String venueMic() {
    return terms.venueMic().value();
  }

  /** Returns the requested order side. */
  public Side side() {
    return terms.side();
  }

  /** Returns the original requested quantity. */
  public BigDecimal quantity() {
    return terms.quantity().value();
  }

  /** Returns the optional limit price. */
  public BigDecimal limitPrice() {
    return terms.limitPrice().value();
  }

  /** Returns the notional currently reported by the reservation lifecycle. */
  public BigDecimal reservedNotional() {
    return state.reservedNotional();
  }

  /** Returns the current reservation status. */
  public ReservationStatus status() {
    return state.outcome().status();
  }

  /** Returns the machine-readable lifecycle reason. */
  public String reasonCode() {
    return state.outcome().reasonCode();
  }

  /** Returns the human-readable lifecycle reason. */
  public String reasonText() {
    return state.outcome().reasonText();
  }

  /** Returns the first timestamp associated with the response. */
  public long createdAtUnixMs() {
    return state.timing().createdAtUnixMs();
  }

  /** Returns the latest timestamp associated with the response. */
  public long updatedAtUnixMs() {
    return state.timing().updatedAtUnixMs();
  }
}
