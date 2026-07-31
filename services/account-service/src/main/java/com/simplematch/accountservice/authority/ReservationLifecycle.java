package com.simplematch.accountservice.authority;

import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.contracts.common.v1.ReservationStatus;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Mutable state machine for one reservation's held authority and execution lifecycle.
 *
 * <p>Quantity, held notional, outcome, and revision change together so terminal states cannot be
 * represented with an active allocation.
 *
 * @param allocation the quantity and notional state owned by this lifecycle
 * @param outcome the status and stable reason owned by this lifecycle
 * @param revision the optimistic version and audit timestamps
 */
public record ReservationLifecycle(
    ReservationAllocation allocation, ReservationOutcome outcome, ReservationRevision revision) {
  /** Enforces state-specific allocation rules independent of the immutable reservation terms. */
  public ReservationLifecycle {
    Objects.requireNonNull(allocation, "allocation");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(revision, "revision");
    switch (outcome.status()) {
      case RESERVATION_STATUS_ACCEPTED -> {
        if (allocation.remainingQuantity().signum() == 0) {
          throw new IllegalArgumentException("accepted reservation must have remaining quantity");
        }
      }
      case RESERVATION_STATUS_REJECTED -> {
        if (allocation.filledQuantity().signum() != 0
            || allocation.reservedNotional().signum() != 0) {
          throw new IllegalArgumentException("rejected reservation must have no held authority");
        }
      }
      case RESERVATION_STATUS_RELEASED -> requireNoHeldAuthority("released", allocation);
      case RESERVATION_STATUS_APPLIED ->
          requireNoHeldAuthority("applied", allocation);
      default -> throw new IllegalArgumentException("status must be specified");
    }
  }

  /**
   * Creates an accepted lifecycle with all requested quantity held.
   *
   * @param terms immutable reservation terms that define the requested quantity
   * @param reservedNotional notional held against the account limit
   * @param now creation timestamp in Unix milliseconds
   * @return an accepted lifecycle with an initial revision
   */
  public static ReservationLifecycle accepted(
      ReservationTerms terms, BigDecimal reservedNotional, long now) {
    Objects.requireNonNull(terms, "terms");
    return new ReservationLifecycle(
        new ReservationAllocation(terms.quantity().value(), BigDecimal.ZERO, reservedNotional),
        ReservationOutcome.accepted(),
        ReservationRevision.initial(now));
  }

  /**
   * Creates a rejected lifecycle with no filled quantity or held authority.
   *
   * <p>The legacy reservation row retains the unfilled requested quantity in
   * {@code remaining_quantity}. Rejected status and zero reserved notional make clear that this
   * quantity is request metadata, not authority held against the account.
   *
   * @param terms immutable reservation terms that define the unfilled request quantity
   * @param reasonCode stable machine-readable rejection reason
   * @param reasonText human-readable rejection detail
   * @param now creation timestamp in Unix milliseconds
   * @return a rejected lifecycle with no held authority
   */
  public static ReservationLifecycle rejected(
      ReservationTerms terms, String reasonCode, String reasonText, long now) {
    Objects.requireNonNull(terms, "terms");
    return new ReservationLifecycle(
        new ReservationAllocation(terms.quantity().value(), BigDecimal.ZERO, BigDecimal.ZERO),
        ReservationOutcome.rejected(reasonCode, reasonText),
        ReservationRevision.initial(now));
  }

  /**
   * Returns the notional released by one fill at the reservation's limit price.
   *
   * @param fill execution fill to account for
   * @param terms immutable reservation terms containing the limit price
   * @return notional released by the fill
   */
  public BigDecimal reservedNotionalReleasedBy(ExecutionFill fill, ReservationTerms terms) {
    Objects.requireNonNull(fill, "fill");
    Objects.requireNonNull(terms, "terms");
    if (terms.limitPrice().value() == null) {
      return BigDecimal.ZERO;
    }
    return allocation
        .reservedNotional()
        .min(terms.limitPrice().value().multiply(fill.quantity().value()));
  }

  /**
   * Applies one fill and changes to applied only when the requested quantity is complete.
   *
   * @param fill execution fill to apply
   * @param terms immutable reservation terms used to calculate released notional
   * @param now transition timestamp in Unix milliseconds
   * @return the lifecycle after applying the fill
   */
  public ReservationLifecycle applyFill(ExecutionFill fill, ReservationTerms terms, long now) {
    Objects.requireNonNull(fill, "fill");
    Objects.requireNonNull(terms, "terms");
    if (outcome.status() != ReservationStatus.RESERVATION_STATUS_ACCEPTED) {
      throw new IllegalArgumentException("reservation is not active");
    }
    if (fill.quantity().value().compareTo(allocation.remainingQuantity()) > 0) {
      throw new IllegalArgumentException("fill quantity exceeds remaining reservation quantity");
    }
    final BigDecimal releasedNotional = reservedNotionalReleasedBy(fill, terms);
    final BigDecimal remaining = allocation.remainingQuantity().subtract(fill.quantity().value());
    final ReservationStatus nextStatus =
        remaining.signum() == 0
            ? ReservationStatus.RESERVATION_STATUS_APPLIED
            : ReservationStatus.RESERVATION_STATUS_ACCEPTED;
    return new ReservationLifecycle(
        new ReservationAllocation(
            remaining,
            allocation.filledQuantity().add(fill.quantity().value()),
            allocation.reservedNotional().subtract(releasedNotional)),
        nextStatus == ReservationStatus.RESERVATION_STATUS_APPLIED
            ? ReservationOutcome.applied()
            : ReservationOutcome.accepted(),
        revision.next(now));
  }

  /**
   * Releases only unused authority and preserves any quantity already filled.
   *
   * @param reason stable release reason
   * @param now transition timestamp in Unix milliseconds
   * @return the released lifecycle, or this lifecycle when it is already terminal
   */
  public ReservationLifecycle release(
      ReleaseReservationOperation.ReleaseReason reason, long now) {
    Objects.requireNonNull(reason, "reason");
    if (outcome.status() != ReservationStatus.RESERVATION_STATUS_ACCEPTED) {
      return this;
    }
    return new ReservationLifecycle(
        new ReservationAllocation(
            BigDecimal.ZERO, allocation.filledQuantity(), BigDecimal.ZERO),
        ReservationOutcome.released(reason.value()),
        revision.next(now));
  }

  /**
   * Validates quantity conservation against the immutable reservation terms.
   *
   * @param terms immutable reservation terms used as the quantity invariant
   */
  public void validateAgainst(ReservationTerms terms) {
    Objects.requireNonNull(terms, "terms");
    final BigDecimal requested = terms.quantity().value();
    if (allocation.filledQuantity().compareTo(requested) > 0) {
      throw new IllegalArgumentException("filled quantity exceeds reservation quantity");
    }
    switch (outcome.status()) {
      case RESERVATION_STATUS_ACCEPTED, RESERVATION_STATUS_APPLIED ->
          validateConservedQuantity(requested);
      case RESERVATION_STATUS_REJECTED -> validateRejectedQuantity(requested);
      case RESERVATION_STATUS_RELEASED -> validateReleasedQuantity();
      default -> throw new IllegalArgumentException("status must be specified");
    }
  }

  private void validateConservedQuantity(BigDecimal requested) {
    if (allocation.filledQuantity().add(allocation.remainingQuantity()).compareTo(requested)
        != 0) {
      throw new IllegalArgumentException("reservation quantities are invalid");
    }
  }

  private void validateRejectedQuantity(BigDecimal requested) {
    if (allocation.filledQuantity().signum() != 0
        || allocation.remainingQuantity().compareTo(requested) != 0) {
      throw new IllegalArgumentException("rejected reservation must retain unfilled quantity");
    }
  }

  private void validateReleasedQuantity() {
    if (allocation.remainingQuantity().signum() != 0) {
      throw new IllegalArgumentException(
          "released reservation must have no remaining quantity");
    }
  }

  private static void requireNoHeldAuthority(String state, ReservationAllocation allocation) {
    if (allocation.remainingQuantity().signum() != 0
        || allocation.reservedNotional().signum() != 0) {
      throw new IllegalArgumentException(state + " reservation must have no held authority");
    }
  }
}
