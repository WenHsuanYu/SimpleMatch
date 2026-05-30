package com.simplematch.riskservice.submission;

/**
 * Persisted result of a risk-service submission attempt.
 *
 * @param idempotencyKey the storage key used to deduplicate repeated submissions
 * @param requestId the persisted operation identifier exposed as {@code request_id} on synchronous
 *     RPC and storage boundaries; this is currently the same underlying value as the ingress
 *     {@code command_id}
 * @param orderId the order identifier carried by the submission
 * @param clientOrderId the client-provided order identifier
 * @param originalClientOrderId the original client order identifier for cancel flows
 * @param commandType the normalized command type selected for the submission
 * @param accepted whether the submission passed validation and persistence
 * @param reasonCode the rejection reason code, or blank when accepted
 * @param reasonText the rejection reason text, or blank when accepted
 * @param createdAtUnixMs the persistence timestamp in epoch milliseconds
 */
public record SubmissionResult(
        String idempotencyKey,
        String requestId,
        String orderId,
        String clientOrderId,
        String originalClientOrderId,
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
}