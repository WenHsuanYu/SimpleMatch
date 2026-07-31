package com.simplematch.riskservice.admission;

import java.util.Objects;

/**
 * Owns the state-specific decision and optimistic revision of one admission aggregate.
 *
 * @param decision pending or terminal admission decision
 * @param version optimistic concurrency version
 * @param createdAtUnixMs creation timestamp in Unix milliseconds
 * @param updatedAtUnixMs latest update timestamp in Unix milliseconds
 */
public record AdmissionLifecycle(
    AdmissionDecision decision, long version, long createdAtUnixMs, long updatedAtUnixMs) {
  /** Validates revision and timestamp invariants for a journal lifecycle. */
  public AdmissionLifecycle {
    Objects.requireNonNull(decision, "decision");
    if (version < 0 || createdAtUnixMs < 0 || updatedAtUnixMs < createdAtUnixMs) {
      throw new IllegalArgumentException("admission lifecycle revision is invalid");
    }
  }

  /**
   * Creates a new pending lifecycle at the supplied timestamp.
   *
   * @param now creation timestamp in Unix milliseconds
   * @return a pending lifecycle at revision zero
   */
  public static AdmissionLifecycle pending(long now) {
    return new AdmissionLifecycle(new AdmissionDecision.Pending(), 0L, now, now);
  }

  /**
   * Returns the storage-compatible state of the owned decision.
   *
   * @return state persisted in the admission journal
   */
  public AdmissionState state() {
    return decision.state();
  }

  /**
   * Applies one account outcome while preserving terminal replay semantics.
   *
   * @param order the validated order whose operation determines the accepted decision kind
   * @param outcome the account reservation outcome
   * @param now the transition timestamp in Unix milliseconds
   * @return the terminal lifecycle, or this lifecycle when it is already terminal
   */
  public AdmissionLifecycle finalizeWith(
      AdmissionOrder order, ReservationOutcome outcome, long now) {
    Objects.requireNonNull(order, "order");
    Objects.requireNonNull(outcome, "outcome");
    if (!(decision instanceof AdmissionDecision.Pending)) {
      return this;
    }
    final AdmissionDecision terminal = terminalDecision(order, outcome);
    return new AdmissionLifecycle(terminal, version + 1, createdAtUnixMs, now);
  }

  private AdmissionDecision terminalDecision(AdmissionOrder order, ReservationOutcome outcome) {
    if (!outcome.accepted()) {
      return new AdmissionDecision.Rejected(
          new AdmissionFailure(
              new AdmissionFailure.ReasonCode(outcome.reasonCode()),
              new AdmissionFailure.Detail(outcome.reasonDetail())));
    }
    if (order.isCancellation()) {
      if (outcome.reservationId() != null) {
        throw new IllegalArgumentException("accepted cancel admission must not have a reservation");
      }
      return new AdmissionDecision.AcceptedCancel();
    }
    if (outcome.reservationId() == null) {
      throw new IllegalArgumentException("accepted new admission requires a reservation");
    }
    return new AdmissionDecision.AcceptedNew(outcome.reservationId());
  }
}
