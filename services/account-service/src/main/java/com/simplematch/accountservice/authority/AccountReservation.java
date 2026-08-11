package com.simplematch.accountservice.authority;

import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable authoritative reservation composed from identity, ownership, terms, and lifecycle.
 *
 * @param identity reservation, request, and order identity
 * @param ownership account ownership
 * @param terms immutable order terms
 * @param lifecycle mutable authority and execution state
 */
public record AccountReservation(
    ReservationIdentity identity,
    ReservationOwnership ownership,
    ReservationTerms terms,
    ReservationLifecycle lifecycle) {
  /** Requires a complete reservation and validates its lifecycle against its immutable terms. */
  public AccountReservation {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(ownership, "ownership");
    Objects.requireNonNull(terms, "terms");
    Objects.requireNonNull(lifecycle, "lifecycle");
    lifecycle.validateAgainst(terms);
  }

  /** Creates an accepted reservation with all requested authority held. */
  public static AccountReservation accepted(
      ReservationIdentity identity,
      ReservationOwnership ownership,
      ReservationTerms terms,
      BigDecimal reservedNotional,
      long now) {
    return new AccountReservation(
        identity, ownership, terms, ReservationLifecycle.accepted(terms, reservedNotional, now));
  }

  /** Creates a rejected reservation without changing account balances. */
  public static AccountReservation rejected(
      ReservationIdentity identity,
      ReservationOwnership ownership,
      ReservationTerms terms,
      String reasonCode,
      String reasonText,
      long now) {
    return new AccountReservation(
        identity,
        ownership,
        terms,
        ReservationLifecycle.rejected(terms, reasonCode, reasonText, now));
  }

  /** Returns the reservation identifier for persistence and external responses. */
  public String reservationId() {
    return identity.reservationId().value();
  }

  /** Returns the idempotent request identifier. */
  public String requestId() {
    return identity.requestId().value();
  }

  /** Returns the owning order identifier. */
  public String orderId() {
    return identity.orderId().value();
  }

  /** Returns the canonical Account-domain identity. */
  public AccountId accountIdentity() {
    return ownership.accountId();
  }

  /** Returns the owning account identifier for boundary projections. */
  public String accountId() {
    return accountIdentity().wireValue();
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

  /** Returns the quantity not yet filled or released. */
  public BigDecimal remainingQuantity() {
    return lifecycle.allocation().remainingQuantity();
  }

  /** Returns the quantity already filled. */
  public BigDecimal filledQuantity() {
    return lifecycle.allocation().filledQuantity();
  }

  /** Returns the optional limit price. */
  public BigDecimal limitPrice() {
    return terms.limitPrice().value();
  }

  /** Returns whether a repeated reserve request describes the same immutable request facts. */
  public boolean hasEquivalentRequestFacts(ReserveOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return orderId().equals(operation.orderId())
        && accountId().equals(operation.accountId())
        && terms.hasEquivalentFacts(operation.terms());
  }

  /** Returns the notional still held by the reservation. */
  public BigDecimal reservedNotional() {
    return lifecycle.allocation().reservedNotional();
  }

  /** Returns the current lifecycle status. */
  public ReservationStatus status() {
    return lifecycle.outcome().status();
  }

  /** Returns the machine-readable lifecycle reason. */
  public String reasonCode() {
    return lifecycle.outcome().reasonCode();
  }

  /** Returns the human-readable lifecycle reason. */
  public String reasonText() {
    return lifecycle.outcome().reasonText();
  }

  /** Returns the optimistic lifecycle version. */
  public long version() {
    return lifecycle.revision().version();
  }

  /** Returns the original persistence timestamp. */
  public long createdAtUnixMs() {
    return lifecycle.revision().createdAtUnixMs();
  }

  /** Returns the latest lifecycle update timestamp. */
  public long updatedAtUnixMs() {
    return lifecycle.revision().updatedAtUnixMs();
  }

  /** Returns the notional released by one execution fill. */
  public BigDecimal reservedNotionalReleasedBy(ExecutionFill fill) {
    return lifecycle.reservedNotionalReleasedBy(fill, terms);
  }

  /** Applies one execution fill to this reservation lifecycle. */
  public AccountReservation applyFill(ExecutionFill fill, long now) {
    Objects.requireNonNull(fill, "fill");
    return new AccountReservation(
        identity, ownership, terms, lifecycle.applyFill(fill, terms, now));
  }

  /** Releases only unused authority and preserves any quantity already filled. */
  public AccountReservation release(ReleaseReservationOperation.ReleaseReason reason, long now) {
    return new AccountReservation(identity, ownership, terms, lifecycle.release(reason, now));
  }
}
