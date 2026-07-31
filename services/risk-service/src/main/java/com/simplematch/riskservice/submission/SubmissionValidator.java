package com.simplematch.riskservice.submission;

import java.time.Clock;
import java.util.Objects;

/** Validates normalized risk submissions before they enter the transactional persistence flow. */
public final class SubmissionValidator {
  private final Clock clock;
  private final SubmissionDecisionFactory decisionFactory;
  private final SubmissionRejectionPolicy rejectionPolicy;

  /**
   * Creates a validator anchored to the provided clock.
   *
   * @param clock the clock used to derive persistence timestamps and fallback trading days
   */
  public SubmissionValidator(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
    decisionFactory = new SubmissionDecisionFactory(clock);
    rejectionPolicy = new SubmissionRejectionPolicy();
  }

  /**
   * Evaluates a normalized submission command and returns the accepted or rejected decision.
   *
   * @param command the normalized submission command, or {@code null} for an unspecified command
   * @return the submission decision that should be persisted
   */
  public SubmissionDecision evaluate(ResolvedSubmissionCommand command) {
    final ResolvedSubmissionCommand normalizedCommand =
        command == null ? ResolvedSubmissionCommand.unspecified() : command;
    final long now = clock.instant().toEpochMilli();
    final SubmissionRejection rejection = rejectionPolicy.firstRejection(normalizedCommand);
    if (rejection != null) {
      return decisionFactory.rejected(normalizedCommand, now, rejection);
    }
    return decisionFactory.accepted(normalizedCommand, now);
  }
}
