package com.simplematch.riskservice.submission;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Canonical FIX-facing business identity for a persisted risk submission.
 *
 * @param sessionId the originating FIX session identifier
 * @param tradingDay the UTC trading day derived from gateway ingress time
 * @param commandType the normalized command type
 * @param clientOrderId the client-provided order identifier
 */
public record SubmissionBusinessKey(
    String sessionId,
    LocalDate tradingDay,
    CommandType commandType,
    String clientOrderId) {

  /**
   * Creates a business key from a persisted submission result.
   *
   * @param submission the persisted submission result
   * @return the business key used for submission deduplication
   */
  public static SubmissionBusinessKey from(SubmissionResult submission) {
    Objects.requireNonNull(submission, "submission");
    return new SubmissionBusinessKey(
        submission.sessionId(),
        submission.tradingDay(),
        submission.commandType(),
        submission.clientOrderId());
  }
}