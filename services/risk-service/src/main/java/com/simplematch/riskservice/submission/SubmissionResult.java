package com.simplematch.riskservice.submission;

import java.time.LocalDate;

/**
 * Persisted result of a risk-service submission attempt.
 *
 * @param requestId             the persisted operation identifier exposed as {@code request_id} on synchronous
 *                              RPC and storage boundaries; this is currently the same underlying value as the ingress
 *                              {@code command_id}
 * @param senderCompId          the FIX SenderCompID carried by the submission payload
 * @param targetCompId          the FIX TargetCompID carried by the submission payload
 * @param tradingDay            the business trading day derived from the gateway event timestamp in UTC
 * @param orderId               the order identifier carried by the submission
 * @param clOrdId               the raw FIX ClOrdID carried by the submission payload and echoed on RPC responses
 * @param origClOrdId           the raw FIX OrigClOrdID for cancel flows and echoed on RPC responses
 * @param commandType           the normalized command type selected for the submission
 * @param accepted              whether the submission passed validation and persistence
 * @param reasonCode            the rejection reason code, or blank when accepted
 * @param reasonText            the rejection reason text, or blank when accepted
 * @param createdAtUnixMs       the persistence timestamp in epoch milliseconds
 * @param persistedClOrdId      the persisted business-key-safe ClOrdID used for deduplication and
 *                              storage constraints
 * @param persistedOrigClOrdId  the persisted OrigClOrdID value stored in the submission journal
 * @param businessKeySurrogated whether any persisted business-key field was replaced with a
 *                              deterministic surrogate instead of the raw FIX value
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
        long createdAtUnixMs,
        String persistedClOrdId,
        String persistedOrigClOrdId,
        boolean businessKeySurrogated) {

    /**
     * Creates a submission result whose raw and persisted FIX identifiers are identical.
     *
     * @param requestId the persisted operation identifier
     * @param senderCompId the FIX SenderCompID carried by the submission payload
     * @param targetCompId the FIX TargetCompID carried by the submission payload
     * @param tradingDay the business trading day derived from the gateway event timestamp in UTC
     * @param orderId the order identifier carried by the submission
     * @param clOrdId the raw FIX ClOrdID carried by the submission payload
     * @param origClOrdId the raw FIX OrigClOrdID for cancel flows
     * @param commandType the normalized command type selected for the submission
     * @param accepted whether the submission passed validation and persistence
     * @param reasonCode the rejection reason code, or blank when accepted
     * @param reasonText the rejection reason text, or blank when accepted
     * @param createdAtUnixMs the persistence timestamp in epoch milliseconds
     */
    /**
     * @deprecated Use the canonical record constructor; retained for wire compatibility tracked by issue #21.
     */
    @Deprecated(forRemoval = false)
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public SubmissionResult(
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
        this(
                requestId,
                senderCompId,
                targetCompId,
                tradingDay,
                orderId,
                clOrdId,
                origClOrdId,
                commandType,
                accepted,
                reasonCode,
                reasonText,
                createdAtUnixMs,
                clOrdId,
                origClOrdId,
                false);
    }

    /**
     * Creates a submission result with explicit persisted FIX identifiers and a non-surrogated
     * business key.
     *
     * @param requestId the persisted operation identifier
     * @param senderCompId the FIX SenderCompID carried by the submission payload
     * @param targetCompId the FIX TargetCompID carried by the submission payload
     * @param tradingDay the business trading day derived from the gateway event timestamp in UTC
     * @param orderId the order identifier carried by the submission
     * @param clOrdId the raw FIX ClOrdID carried by the submission payload
     * @param origClOrdId the raw FIX OrigClOrdID for cancel flows
     * @param commandType the normalized command type selected for the submission
     * @param accepted whether the submission passed validation and persistence
     * @param reasonCode the rejection reason code, or blank when accepted
     * @param reasonText the rejection reason text, or blank when accepted
     * @param createdAtUnixMs the persistence timestamp in epoch milliseconds
     * @param persistedClOrdId the persisted business-key-safe ClOrdID used for storage
     * @param persistedOrigClOrdId the persisted OrigClOrdID value stored in the journal
     */
    /**
     * @deprecated Use the canonical record constructor; retained for wire compatibility tracked by issue #21.
     */
    @Deprecated(forRemoval = false)
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public SubmissionResult(
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
            long createdAtUnixMs,
            String persistedClOrdId,
            String persistedOrigClOrdId) {
        this(
                requestId,
                senderCompId,
                targetCompId,
                tradingDay,
                orderId,
                clOrdId,
                origClOrdId,
                commandType,
                accepted,
                reasonCode,
                reasonText,
                createdAtUnixMs,
                persistedClOrdId,
                persistedOrigClOrdId,
                false);
    }

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
