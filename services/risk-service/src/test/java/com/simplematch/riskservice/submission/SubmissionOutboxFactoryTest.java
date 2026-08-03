package com.simplematch.riskservice.submission;

import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.cancelOrderPayload;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.newOrderPayload;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedNewOrder;
import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;
import static com.simplematch.riskservice.testsupport.SubmissionResultFixtures.acceptedCancelOrder;
import static com.simplematch.riskservice.testsupport.SubmissionResultFixtures.acceptedNewOrder;
import static com.simplematch.riskservice.testsupport.SubmissionResultFixtures.rejectedEmptyCommand;
import static com.simplematch.riskservice.testsupport.SubmissionResultFixtures.rejectedMissingPrice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.SubmissionOutboxFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmissionOutboxFactoryTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SubmissionOutboxFactory factory =
      new SubmissionOutboxFactory(
          objectMapper, "orders.validated", symbol -> "AAPL".equals(symbol) ? 7 : 0);

  @Test
  void createsAcceptedOutboxRecord() throws Exception {
    final SubmissionDecision decision = acceptedDecision();

    final OutboxRecord record = factory.create(decision);
    final OrderValidated payload =
        OrderValidated.parseFrom(record.payloadEnvelope().payload());
    final JsonNode headers = objectMapper.readTree(record.payloadEnvelope().headersJson());

    assertThat(record.routing().topic()).isEqualTo("orders.validated");
    assertThat(record.routing().messageKey()).isEqualTo("AAPL");
    assertThat(record.routing().kafkaPartitionId()).isEqualTo(7);
    assertThat(record.payloadEnvelope().payloadType())
        .isEqualTo(OrderValidated.getDescriptor().getFullName());
    assertThat(record.aggregateReference().aggregateType()).isEqualTo("risk_submission");
    assertThat(record.aggregateReference().aggregateId()).isEqualTo("O-C1");
    assertThat(record.eventInfo().createdAtUnixMs()).isEqualTo(100L);
    assertUuidVersionSeven(record.eventInfo().eventId());
    assertThat(headers.get("event_id").asText()).isEqualTo(record.eventInfo().eventId());
    assertThat(headers.get("content_type").asText()).isEqualTo("application/x-protobuf");
    assertThat(headers.get("payload_type").asText())
        .isEqualTo(record.payloadEnvelope().payloadType());
    assertThat(payload.getMetadata().getEventId()).isEqualTo(record.eventInfo().eventId());
    assertThat(payload.getMetadata().getCreatedAtUnixMs()).isEqualTo(100L);
    assertThat(payload.getMetadata().getSourceService()).isEqualTo("risk-service");
    assertThat(payload.getCommandId()).isEqualTo(normalize("cmd-1"));
    assertThat(payload.getOrderId()).isEqualTo("O-C1");
    assertThat(payload.getAccountId()).isEqualTo("ACC-1");
    assertThat(payload.getSymbol()).isEqualTo("AAPL");
    assertThat(payload.getRoutingPartition()).isEqualTo("7");
  }

  @Test
  void createsRejectedOutboxRecord() throws Exception {
    final SubmissionDecision decision = rejectedDecision();

    final OutboxRecord record = factory.create(decision);
    final OrderRejected payload =
        OrderRejected.parseFrom(record.payloadEnvelope().payload());
    final JsonNode headers = objectMapper.readTree(record.payloadEnvelope().headersJson());

    assertThat(record.routing().messageKey()).isEqualTo("AAPL");
    assertThat(record.payloadEnvelope().payloadType())
        .isEqualTo(OrderRejected.getDescriptor().getFullName());
    assertUuidVersionSeven(record.eventInfo().eventId());
    assertThat(headers.get("event_id").asText()).isEqualTo(record.eventInfo().eventId());
    assertThat(headers.get("payload_type").asText())
        .isEqualTo(record.payloadEnvelope().payloadType());
    assertThat(payload.getCommandId()).isEqualTo(normalize("cmd-1"));
    assertThat(payload.getOrderId()).isEqualTo("O-C1");
    assertThat(payload.getAccountId()).isEqualTo("ACC-1");
    assertThat(payload.getSymbol()).isEqualTo("AAPL");
    assertThat(payload.getRejectReasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(payload.getRejectReasonText()).isEqualTo("price is required for limit orders");
  }

  @Test
  void fallsBackToOrderIdWhenSymbolIsMissing() {
    final SubmissionCommand command = cancelOrderPayload("cmd-2", "O-C1", "CXL-1", "C1");
    final SubmissionDecision decision =
        new SubmissionDecision(
            acceptedCancelOrder(),
            new ResolvedSubmissionCommand(command, CommandType.COMMAND_TYPE_CANCEL));

    final OutboxRecord record = factory.create(decision);

    assertThat(record.routing().messageKey()).isEqualTo("O-C1");
  }

  @Test
  void fallsBackToUnknownWhenSymbolAndOrderIdAreMissing() {
    final SubmissionDecision decision =
        new SubmissionDecision(
            rejectedEmptyCommand(),
            ResolvedSubmissionCommand.unspecified());

    final OutboxRecord record = factory.create(decision);

    assertThat(record.routing().messageKey()).isEqualTo("UNKNOWN");
  }

  @Test
  void createsDistinctEventIdsForEquivalentSubmission() {
    final SubmissionDecision decision = acceptedDecision();

    final OutboxRecord first = factory.create(decision);
    final OutboxRecord second = factory.create(decision);

    assertThat(first.eventInfo().eventId()).isNotEqualTo(second.eventInfo().eventId());
    assertUuidVersionSeven(first.eventInfo().eventId());
    assertUuidVersionSeven(second.eventInfo().eventId());
  }

  @Test
  void wrapsHeaderSerializationFailures() {
    final SubmissionOutboxFactory failingFactory =
        new SubmissionOutboxFactory(failingObjectMapper(), "orders.validated", symbol -> 7);

    assertThatThrownBy(() -> failingFactory.create(acceptedDecision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to serialize outbox headers");
  }

  private SubmissionDecision acceptedDecision() {
    return new SubmissionDecision(
        acceptedNewOrder(),
        resolvedNewOrder("cmd-1", "O-C1", "C1"));
  }

  private SubmissionDecision rejectedDecision() {
    final SubmissionCommand command =
        newOrderPayload("cmd-1", "O-C1", "C1", "", OrderType.ORDER_TYPE_LIMIT);
    return new SubmissionDecision(
        rejectedMissingPrice(),
        new ResolvedSubmissionCommand(command, CommandType.COMMAND_TYPE_NEW));
  }

  private void assertUuidVersionSeven(String rawUuid) {
    assertThat(UUID.fromString(rawUuid).version()).isEqualTo(7);
  }

  private ObjectMapper failingObjectMapper() {
    return new ObjectMapper() {
      @Override
      public String writeValueAsString(Object value) throws JsonProcessingException {
        throw new JsonProcessingException("boom") {
          private static final long serialVersionUID = 1L;
        };
      }
    };
  }
}
