package com.simplematch.quickfixgateway.risk;

import java.util.Objects;

/** Captures a confirmed or unresolved risk-admission outcome for one command. */
public record RiskSubmissionResult(
    String orderId, Outcome outcome, String reasonCode, String reasonText) {
  /** Requires every result to carry an explicit outcome classification. */
  public RiskSubmissionResult {
    Objects.requireNonNull(outcome, "outcome");
  }

  /** Creates a terminal result from the legacy accepted/rejected response flag. */
  public RiskSubmissionResult(
      String orderId, boolean accepted, String reasonCode, String reasonText) {
    this(orderId, accepted ? Outcome.ACCEPTED : Outcome.REJECTED, reasonCode, reasonText);
  }

  /** Creates an unresolved result when transport failure hides the authoritative Risk decision. */
  public static RiskSubmissionResult unknown(
      String orderId, String reasonCode, String reasonText) {
    return new RiskSubmissionResult(orderId, Outcome.UNKNOWN, reasonCode, reasonText);
  }

  /** Returns whether the caller could not confirm Risk's authoritative decision. */
  public boolean unknown() {
    return outcome == Outcome.UNKNOWN;
  }

  /** Returns whether Risk explicitly confirmed acceptance. */
  public boolean accepted() {
    return outcome == Outcome.ACCEPTED;
  }

  /** Returns whether Risk explicitly confirmed business rejection. */
  public boolean rejected() {
    return outcome == Outcome.REJECTED;
  }

  /** Distinguishes confirmed terminal decisions from transport-level uncertainty. */
  public enum Outcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN
  }
}
