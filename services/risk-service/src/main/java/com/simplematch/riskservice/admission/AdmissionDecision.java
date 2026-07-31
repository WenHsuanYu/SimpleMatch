package com.simplematch.riskservice.admission;

import java.util.Objects;
import java.util.UUID;

/**
 * State-specific terminal or pending decision for one admission lifecycle.
 *
 * <p>The decision deliberately carries only the outcome-specific data needed by the journal and
 * its response projection.
 */
public sealed interface AdmissionDecision
    permits AdmissionDecision.Pending,
        AdmissionDecision.AcceptedNew,
        AdmissionDecision.AcceptedCancel,
        AdmissionDecision.Rejected {
  /** Returns the storage-compatible admission state represented by this decision. */
  AdmissionState state();

  /** Returns the accepted-new reservation identity, or {@code null} for other decisions. */
  default UUID reservationId() {
    return this instanceof AcceptedNew accepted ? accepted.reservationId() : null;
  }

  /** Returns the rejection code, or an empty value for non-rejected decisions. */
  default String reasonCode() {
    return this instanceof Rejected rejected ? rejected.failure().reasonCode().value() : "";
  }

  /** Returns rejection detail, or an empty value for non-rejected decisions. */
  default String reasonDetail() {
    return this instanceof Rejected rejected ? rejected.failure().detail().value() : "";
  }

  /** Represents a journaled admission awaiting the account outcome. */
  record Pending() implements AdmissionDecision {
    /** Returns the pending storage state. */
    @Override
    public AdmissionState state() {
      return AdmissionState.PENDING;
    }
  }

  /**
   * Represents an accepted new order with its account reservation identity.
   *
   * @param reservationId reservation created by account authority
   */
  record AcceptedNew(UUID reservationId) implements AdmissionDecision {
    /** Requires the reservation created for the accepted new order. */
    public AcceptedNew {
      Objects.requireNonNull(reservationId, "reservationId");
    }

    /** Returns the accepted storage state. */
    @Override
    public AdmissionState state() {
      return AdmissionState.ACCEPTED;
    }
  }

  /** Represents an accepted cancellation, which has no new reservation. */
  record AcceptedCancel() implements AdmissionDecision {
    /** Returns the accepted storage state. */
    @Override
    public AdmissionState state() {
      return AdmissionState.ACCEPTED;
    }
  }

  /**
   * Represents a rejected admission with a stable failure reason.
   *
   * @param failure stable rejection code and detail
   */
  record Rejected(AdmissionFailure failure) implements AdmissionDecision {
    /** Requires a complete nonblank rejection failure. */
    public Rejected {
      Objects.requireNonNull(failure, "failure");
    }

    /** Returns the rejected storage state. */
    @Override
    public AdmissionState state() {
      return AdmissionState.REJECTED;
    }
  }
}
