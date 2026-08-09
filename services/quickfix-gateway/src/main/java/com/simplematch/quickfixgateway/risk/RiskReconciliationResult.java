package com.simplematch.quickfixgateway.risk;

import java.util.Objects;

/** Authoritative Risk admission snapshot used to reconcile an unresolved gateway command. */
public record RiskReconciliationResult(
    String commandId, Outcome outcome, String orderId, String reasonCode, String reasonDetail) {
  /** Normalizes optional response text while requiring command identity and outcome. */
  public RiskReconciliationResult {
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(outcome, "outcome");
    orderId = orderId == null ? "" : orderId;
    reasonCode = reasonCode == null ? "" : reasonCode;
    reasonDetail = reasonDetail == null ? "" : reasonDetail;
  }

  /** Returns whether Risk has no durable admission row for the command. */
  public boolean notFound() {
    return outcome == Outcome.NOT_FOUND;
  }

  /** Returns whether Risk durably owns an admission that is still unresolved. */
  public boolean pending() {
    return outcome == Outcome.PENDING;
  }

  /** Returns whether Risk authoritatively accepted the command. */
  public boolean accepted() {
    return outcome == Outcome.ACCEPTED;
  }

  /** Returns whether Risk authoritatively rejected the command. */
  public boolean rejected() {
    return outcome == Outcome.REJECTED;
  }

  /** Distinguishes absence, durable in-flight work, and terminal Risk outcomes. */
  public enum Outcome {
    NOT_FOUND,
    PENDING,
    ACCEPTED,
    REJECTED
  }
}
