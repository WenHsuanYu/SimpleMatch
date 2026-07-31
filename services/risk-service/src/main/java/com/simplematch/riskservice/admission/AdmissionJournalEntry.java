package com.simplematch.riskservice.admission;

import java.util.Objects;

/**
 * Durable admission aggregate composed from command, delivery route, and lifecycle state.
 *
 * @param command validated order and ingress facts
 * @param route persisted delivery route
 * @param lifecycle state-specific decision and optimistic revision
 */
public record AdmissionJournalEntry(
    AdmissionCommand command, AdmissionDeliveryRoute route, AdmissionLifecycle lifecycle) {
  /** Requires all semantic portions of the journal aggregate. */
  public AdmissionJournalEntry {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(lifecycle, "lifecycle");
  }

  /**
   * Creates a pending journal entry from validated command facts and its persisted route.
   *
   * @param command validated admission command
   * @param route route persisted with the journal row
   * @param now creation timestamp in Unix milliseconds
   * @return a pending journal entry
   */
  public static AdmissionJournalEntry pending(
      AdmissionCommand command, AdmissionDeliveryRoute route, long now) {
    return new AdmissionJournalEntry(command, route, AdmissionLifecycle.pending(now));
  }

  /**
   * Returns whether a retry carries the same persisted command content.
   *
   * @param candidate command supplied by the retry
   * @return whether the candidate matches this journal command
   */
  public boolean matches(AdmissionCommand candidate) {
    return command.equals(Objects.requireNonNull(candidate, "candidate"));
  }

  /**
   * Applies one account outcome to this admission aggregate.
   *
   * <p>Terminal admissions are immutable under replay, so a repeated outcome returns the existing
   * terminal snapshot rather than creating another transition.
   *
   * @param outcome the accepted or rejected account reservation outcome
   * @param now the transition timestamp in Unix milliseconds
   * @return the transitioned admission, or this instance when already terminal
   */
  public AdmissionJournalEntry finalizeWith(ReservationOutcome outcome, long now) {
    if (!(lifecycle.decision() instanceof AdmissionDecision.Pending)) {
      return this;
    }
    final AdmissionLifecycle nextLifecycle = lifecycle.finalizeWith(command.order(), outcome, now);
    return new AdmissionJournalEntry(
        command, route, nextLifecycle);
  }

}
