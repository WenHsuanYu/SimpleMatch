package com.simplematch.riskservice.submission;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Canonical FIX-facing business identity for a persisted risk submission.
 *
 * @param senderCompId the originating FIX SenderCompID
 * @param targetCompId the originating FIX TargetCompID
 * @param tradingDay the UTC trading day derived from gateway ingress time
 * @param commandType the normalized command type
 * @param clOrdId the FIX ClOrdID carried by the submission payload
 * @param businessKeySurrogated whether the persisted business key uses a deterministic surrogate
 */
public record SubmissionBusinessKey(String senderCompId, String targetCompId,
                LocalDate tradingDay, CommandType commandType, String clOrdId, boolean businessKeySurrogated) {
    /**
     * Creates a business key from a persisted submission result.
     *
     * @param submission the persisted submission result
     * @return the business key used for submission deduplication
     */
    public static SubmissionBusinessKey from(SubmissionResult submission) {
        Objects.requireNonNull(submission, "submission");
        return new SubmissionBusinessKey(
            submission.senderCompId(),
            submission.targetCompId(),
            submission.tradingDay(),
            submission.commandType(),
            submission.persistedClOrdId(),
            submission.businessKeySurrogated());
    }
}