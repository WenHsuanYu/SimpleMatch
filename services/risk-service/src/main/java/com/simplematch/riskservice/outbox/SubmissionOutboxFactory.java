package com.simplematch.riskservice.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.SimpleMatchUuids;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import com.simplematch.riskservice.admission.AdmissionOutboxFactory;
import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionDecision;
import com.simplematch.riskservice.submission.SubmissionResult;
import java.util.Objects;

/** Aggregate-specific outbox factory for risk submission events. */
public final class SubmissionOutboxFactory extends AbstractOutboxEventFactory<SubmissionDecision> {
  private static final String AGGREGATE_TYPE = "risk_submission";
  private static final String CONTENT_TYPE = "application/x-protobuf";
  private static final int MAX_MESSAGE_KEY_LENGTH = 255;

  private final String ordersValidatedTopic;
  private final RoutingPartitionResolver routingPartitionResolver;

  /**
   * Creates the legacy v1 factory with an explicit partition provider.
   *
   * <p>The provider is a compatibility seam only. Production admission uses the v2 local Routing
   * Policy projection and {@link AdmissionOutboxFactory}; this legacy factory has no default or
   * hash-based partition fallback.
   */
  public SubmissionOutboxFactory(
      ObjectMapper objectMapper,
      String ordersValidatedTopic,
      RoutingPartitionResolver routingPartitionResolver) {
    super(objectMapper, CONTENT_TYPE);
    this.ordersValidatedTopic =
        Objects.requireNonNull(ordersValidatedTopic, "ordersValidatedTopic");
    this.routingPartitionResolver =
        Objects.requireNonNull(routingPartitionResolver, "routingPartitionResolver");
  }

  @Override
  protected OutboxEvent buildEvent(SubmissionDecision decision) {
    final SubmissionDecision resolvedDecision = Objects.requireNonNull(decision, "decision");
    final SubmissionResult submission = resolvedDecision.submission();
    final SubmissionCommand command = resolvedDecision.command().payload();
    final String eventId = eventId(submission);
    final String payloadType = payloadType(submission);
    final Integer kafkaPartitionId = kafkaPartitionId(resolvedDecision, command);

    return new OutboxEvent(
        new OutboxRecord.EventInfo(eventId, submission.createdAtUnixMs()),
        OutboxRecord.Routing.of(ordersValidatedTopic, messageKey(command), kafkaPartitionId),
        new SerializedPayload(
            payloadBytes(submission, command, eventId, kafkaPartitionId), payloadType),
        new OutboxRecord.AggregateRef(AGGREGATE_TYPE, submission.orderId()));
  }

  private byte[] payloadBytes(
      SubmissionResult submission,
      SubmissionCommand command,
      String eventId,
      Integer kafkaPartitionId) {
    final SubmissionCommand.RequestIdentity identity = command.requestMetadata().identity();
    final String accountId = identity.accountId().value();
    final String symbol = command.orderDetails().symbol();

    if (submission.accepted()) {
      if (kafkaPartitionId == null) {
        throw new IllegalStateException(
            "accepted legacy submission requires an explicit routing partition");
      }
      return OrderValidated.newBuilder()
          .setMetadata(eventMetadata(eventId, submission.createdAtUnixMs()))
          .setCommandId(submission.commandId())
          .setOrderId(submission.orderId())
          .setAccountId(accountId)
          .setSymbol(symbol)
          .setRoutingPartition(Integer.toString(kafkaPartitionId))
          .build()
          .toByteArray();
    }

    return OrderRejected.newBuilder()
        .setMetadata(eventMetadata(eventId, submission.createdAtUnixMs()))
        .setCommandId(submission.commandId())
        .setOrderId(submission.orderId())
        .setAccountId(accountId)
        .setSymbol(symbol)
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

  private String messageKey(SubmissionCommand command) {
    if (command != null && isPersistableMessageKey(command.orderDetails().symbol())) {
      return command.orderDetails().symbol();
    }
    if (command != null && !command.requestMetadata().identity().orderId().isBlank()) {
      return command.requestMetadata().identity().orderId().value();
    }
    return "UNKNOWN";
  }

  private Integer kafkaPartitionId(
      SubmissionDecision decision, SubmissionCommand command) {
    if (!decision.submission().accepted()) {
      return null;
    }
    if (command == null) {
      throw new IllegalStateException(
          "accepted legacy submission requires an explicit routing key");
    }
    final String routingKey = messageKey(command);
    if ("UNKNOWN".equals(routingKey)) {
      throw new IllegalStateException(
          "accepted legacy submission requires a persistable routing key");
    }
    final int partition = routingPartitionResolver.resolve(routingKey);
    if (partition < 0) {
      throw new IllegalStateException("legacy routing provider returned a negative partition");
    }
    return partition;
  }

  private boolean isPersistableMessageKey(String value) {
    return value != null && !value.isBlank() && value.length() <= MAX_MESSAGE_KEY_LENGTH;
  }

  private String eventId(SubmissionResult submission) {
    Objects.requireNonNull(submission, "submission");
    return SimpleMatchUuids.uuidV7().toString();
  }
}
