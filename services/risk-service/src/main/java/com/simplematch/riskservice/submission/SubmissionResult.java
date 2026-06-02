package com.simplematch.riskservice.submission;

import java.time.LocalDate;

/**
 * Persisted result of a risk-service submission attempt.
 *
 * @param requestId the persisted operation identifier exposed as {@code request_id} on synchronous
 *     RPC and storage boundaries; this is currently the same underlying value as the ingress
 *     {@code command_id}
 * @param senderCompId the FIX SenderCompID carried by the submission payload
 * @param targetCompId the FIX TargetCompID carried by the submission payload
 * @param tradingDay the business trading day derived from the gateway event timestamp in UTC
 * @param orderId the order identifier carried by the submission
 * @param clOrdId the FIX ClOrdID carried by the submission payload
 * @param origClOrdId the FIX OrigClOrdID for cancel flows
 * @param commandType the normalized command type selected for the submission
 * @param accepted whether the submission passed validation and persistence
 * @param reasonCode the rejection reason code, or blank when accepted
 * @param reasonText the rejection reason text, or blank when accepted
 * @param createdAtUnixMs the persistence timestamp in epoch milliseconds
 */
public record SubmissionResult(
        String requestId,
        String senderCompId,
        String targetCompId,
        LocalDate tradingDay,
        String orderId,
        String clOrdId,
        String origClOrdId,
        CommandType commandType,
        boolean accepted,
        String reasonCode,
        String reasonText,
        long createdAtUnixMs) {

    /**
     * Returns the event-layer name for the persisted operation identifier.
     *
     * @return the same value stored in {@link #requestId()}
     */
    public String commandId() {
        return requestId;
    }

    /**
     * Returns the FIX-facing business key for this persisted submission.
     *
     * @return the business key derived from sender/target, trading day, command type, and ClOrdID
     */
    public SubmissionBusinessKey businessKey() {
        return SubmissionBusinessKey.from(this);
    }
}