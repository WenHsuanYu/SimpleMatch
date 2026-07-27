package com.simplematch.riskservice.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.SimpleMatchUuids;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionDecision;
import com.simplematch.riskservice.submission.SubmissionResult;
import java.util.Objects;

/**
 * Aggregate-specific outbox factory for risk submission events.
 */
public final class SubmissionOutboxFactory extends AbstractOutboxEventFactory<SubmissionDecision> {
    private static final String AGGREGATE_TYPE = "risk_submission";
    private static final String CONTENT_TYPE = "application/x-protobuf";
    private static final int DEFAULT_PARTITION_ID = 0;
    private static final int MAX_MESSAGE_KEY_LENGTH = 255;

    private final String ordersValidatedTopic;
    private final RoutingPartitionResolver routingPartitionResolver;

    public SubmissionOutboxFactory(ObjectMapper objectMapper, String ordersValidatedTopic) {
        this(objectMapper, ordersValidatedTopic, symbol -> DEFAULT_PARTITION_ID);
    }

    public SubmissionOutboxFactory(ObjectMapper objectMapper, String ordersValidatedTopic,
            RoutingPartitionResolver routingPartitionResolver) {
        super(objectMapper, CONTENT_TYPE);
        this.ordersValidatedTopic = Objects.requireNonNull(ordersValidatedTopic, "ordersValidatedTopic");
        this.routingPartitionResolver = Objects.requireNonNull(routingPartitionResolver, "routingPartitionResolver");
    }

    @Override
    protected OutboxEvent buildEvent(SubmissionDecision decision) {
        final SubmissionDecision resolvedDecision = Objects.requireNonNull(decision, "decision");
        final SubmissionResult submission = resolvedDecision.submission();
        final SubmissionCommand command = resolvedDecision.command().payload();
        final String eventId = eventId(submission);
        final String payloadType = payloadType(submission);
        final Integer kafkaPartitionId = kafkaPartitionId(command);

        return new OutboxEvent(
                eventId,
                submission.createdAtUnixMs(),
                ordersValidatedTopic,
                messageKey(command),
                kafkaPartitionId,
                payloadBytes(submission, command, eventId, kafkaPartitionId),
                payloadType,
                AGGREGATE_TYPE,
                submission.orderId());
    }

    private byte[] payloadBytes(SubmissionResult submission, SubmissionCommand command,
            String eventId, int kafkaPartitionId) {

        if (submission.accepted()) {
            return OrderValidated.newBuilder()
                    .setMetadata(eventMetadata(eventId, submission.createdAtUnixMs()))
                    .setCommandId(submission.commandId())
                    .setOrderId(submission.orderId())
                    .setAccountId(command.accountId())
                    .setSymbol(command.symbol())
                    .setRoutingPartition(Integer.toString(kafkaPartitionId))
                    .build()
                    .toByteArray();
        }

        return OrderRejected.newBuilder()
                .setMetadata(eventMetadata(eventId, submission.createdAtUnixMs()))
                .setCommandId(submission.commandId())
                .setOrderId(submission.orderId())
                .setAccountId(command.accountId())
                .setSymbol(command.symbol())
                .setRejectReasonCode(submission.reasonCode())
                .setRejectReasonText(submission.reasonText())
                .build()
                .toByteArray();
    }

    private EventMetadata eventMetadata(String eventId, long createdAtUnixMs) {
        return EventMetadata.newBuilder()
                .setSchemaVersion("v1")
                .setEventId(eventId)
                .setCreatedAtUnixMs(createdAtUnixMs)
                .setSourceService("risk-service")
                .build();
    }

    private String payloadType(SubmissionResult submission) {
        return submission.accepted() ? OrderValidated.getDescriptor().getFullName()
                : OrderRejected.getDescriptor().getFullName();
    }

    private String messageKey(SubmissionCommand command) {
        if (command != null && isPersistableMessageKey(command.symbol())) {
            return command.symbol();
        }
        if (command != null && !command.orderIdValue().isBlank()) {
            return command.orderIdValue().value();
        }
        return "UNKNOWN";
    }

    private int kafkaPartitionId(SubmissionCommand command) {
        if (command == null || !isPersistableMessageKey(command.symbol())) {
            return DEFAULT_PARTITION_ID;
        }
        return routingPartitionResolver.resolve(command.symbol());
    }

    private boolean isPersistableMessageKey(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_MESSAGE_KEY_LENGTH;
    }

    private String eventId(SubmissionResult submission) {
        Objects.requireNonNull(submission, "submission");
        return SimpleMatchUuids.uuidV7().toString();
    }
}