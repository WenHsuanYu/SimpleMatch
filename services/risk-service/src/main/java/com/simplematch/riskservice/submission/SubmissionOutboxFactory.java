package com.simplematch.riskservice.submission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SubmissionOutboxFactory {
  private static final String AGGREGATE_TYPE = "risk_submission";
  private static final String CONTENT_TYPE = "application/x-protobuf";

  private final ObjectMapper objectMapper;
  private final String ordersValidatedTopic;

  public SubmissionOutboxFactory(ObjectMapper objectMapper, String ordersValidatedTopic) {
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.ordersValidatedTopic = Objects.requireNonNull(ordersValidatedTopic);
  }

  public OutboxRecord create(SubmissionDecision decision) {
    final SubmissionResult submission = decision.submission();
    final SubmissionCommand command = decision.command();
    final String eventId = eventId(submission);
    final String payloadType = payloadType(submission);

    return new OutboxRecord(
        eventId,
        ordersValidatedTopic,
        messageKey(command),
        payloadBytes(submission, command, eventId),
        payloadType,
        headersJson(eventId, payloadType),
        AGGREGATE_TYPE,
        submission.orderId(),
        submission.createdAtUnixMs());
  }

  private byte[] payloadBytes(SubmissionResult submission, SubmissionCommand command, String eventId) {
    if (submission.accepted()) {
      return OrderValidated.newBuilder()
          .setMetadata(eventMetadata(eventId, submission.createdAtUnixMs()))
          .setCommandId(submission.requestId())
          .setOrderId(submission.orderId())
          .setAccountId(command.accountId())
          .setSymbol(command.symbol())
          .build()
          .toByteArray();
    }

    return OrderRejected.newBuilder()
        .setMetadata(eventMetadata(eventId, submission.createdAtUnixMs()))
        .setCommandId(submission.requestId())
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
    return submission.accepted()
        ? OrderValidated.getDescriptor().getFullName()
        : OrderRejected.getDescriptor().getFullName();
  }

  private String headersJson(String eventId, String payloadType) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "event_id", eventId,
          "content_type", CONTENT_TYPE,
          "payload_type", payloadType));
    } catch (JsonProcessingException jsonProcessingException) {
      throw new IllegalStateException("failed to serialize outbox headers", jsonProcessingException);
    }
  }

  private String messageKey(SubmissionCommand command) {
    if (command != null && !command.symbol().isBlank()) {
      return command.symbol();
    }
    if (command != null && !command.orderId().isBlank()) {
      return command.orderId();
    }
    return "UNKNOWN";
  }

  private String eventId(SubmissionResult submission) {
    final String source = submission.idempotencyKey()
        + "|"
        + submission.requestId()
        + "|"
        + submission.orderId()
        + "|"
        + submission.reasonCode()
        + "|"
        + submission.accepted();
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
  }
}