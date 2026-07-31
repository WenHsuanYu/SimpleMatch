package com.simplematch.riskservice.submission;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Persisted result of a risk-service submission attempt.
 *
 * <p>The result is composed from domain values instead of one flat primitive list: the submission
 * reference, FIX identity, storage-safe FIX identity, and accepted-or-rejected outcome each own a
 * distinct invariant and change reason.
 *
 * @param reference the operation and order reference
 * @param fixIdentity the FIX-facing business identity
 * @param persistedFixIdentity the storage-safe FIX identity
 * @param outcome the accepted or rejected result
 * @param createdAtUnixMs the persistence timestamp in epoch milliseconds
 */
public record SubmissionResult(
    SubmissionReference reference,
    FixSubmissionIdentity fixIdentity,
    PersistedFixIdentity persistedFixIdentity,
    SubmissionOutcome outcome,
    long createdAtUnixMs) {

  /** Requires complete domain values and a non-negative timestamp. */
  public SubmissionResult {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(fixIdentity, "fixIdentity");
    Objects.requireNonNull(persistedFixIdentity, "persistedFixIdentity");
    Objects.requireNonNull(outcome, "outcome");
    if (createdAtUnixMs < 0) {
      throw new IllegalArgumentException("created_at_unix_ms must be non-negative");
    }
  }

  /** Returns the persisted operation identifier. */
  public String requestId() {
    return reference.requestId().value();
  }

  /** Returns the event-layer name for the persisted operation identifier. */
  public String commandId() {
    return reference.requestId().value();
  }

  /** Returns the order identifier. */
  public String orderId() {
    return reference.orderId().value();
  }

  /** Returns the normalized command type. */
  public CommandType commandType() {
    return reference.commandType();
  }

  /** Returns the persisted-safe FIX SenderCompID. */
  public String senderCompId() {
    return fixIdentity.senderCompId().value();
  }

  /** Returns the persisted-safe FIX TargetCompID. */
  public String targetCompId() {
    return fixIdentity.targetCompId().value();
  }

  /** Returns the business trading day. */
  public LocalDate tradingDay() {
    return fixIdentity.tradingDay();
  }

  /** Returns the raw client order identifier. */
  public String clOrdId() {
    return fixIdentity.clOrdId().value();
  }

  /** Returns the raw original client order identifier. */
  public String origClOrdId() {
    return fixIdentity.origClOrdId().value();
  }

  /** Returns the storage-safe client order identifier. */
  public String persistedClOrdId() {
    return persistedFixIdentity.clOrdId().value();
  }

  /** Returns the storage-safe original client order identifier. */
  public String persistedOrigClOrdId() {
    return persistedFixIdentity.origClOrdId().value();
  }

  /** Returns whether a deterministic surrogate participates in the business key. */
  public boolean businessKeySurrogated() {
    return persistedFixIdentity.surrogated();
  }

  /** Returns whether validation accepted the submission. */
  public boolean accepted() {
    return outcome.accepted();
  }

  /** Returns the rejection code, or blank for acceptance. */
  public String reasonCode() {
    return outcome.reasonCode();
  }

  /** Returns the rejection detail, or blank for acceptance. */
  public String reasonText() {
    return outcome.reasonText();
  }

  /** Returns the FIX-facing business key for this persisted submission. */
  public SubmissionBusinessKey businessKey() {
    return SubmissionBusinessKey.from(this);
  }
}
