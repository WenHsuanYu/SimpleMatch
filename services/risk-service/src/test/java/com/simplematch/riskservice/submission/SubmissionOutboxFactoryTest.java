package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.cancelOrderPayload;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.newOrderPayload;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedNewOrder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmissionOutboxFactoryTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SubmissionOutboxFactory factory = new SubmissionOutboxFactory(
      objectMapper,
      "orders.validated",
      symbol -> "AAPL".equals(symbol) ? 7 : 0);

  @Test
  void createsAcceptedOutboxRecord() throws Exception {
    final SubmissionDecision decision = acceptedDecision();

    final OutboxRecord record = factory.create(decision);
    final OrderValidated payload = OrderValidated.parseFrom(record.payload());
    final JsonNode headers = objectMapper.readTree(record.headersJson());

    assertThat(record.topic()).isEqualTo("orders.validated");
    assertThat(record.messageKey()).isEqualTo("AAPL");
    assertThat(record.kafkaPartitionId()).isEqualTo(7);
    assertThat(record.payloadType()).isEqualTo(OrderValidated.getDescriptor().getFullName());
    assertThat(record.aggregateType()).isEqualTo("risk_submission");
    assertThat(record.aggregateId()).isEqualTo("O-C1");
    assertThat(record.createdAtUnixMs()).isEqualTo(100L);
    assertThat(record.eventId()).isEqualTo(expectedEventId(decision.submission()));
    assertThat(headers.get("event_id").asText()).isEqualTo(record.eventId());
    assertThat(headers.get("content_type").asText()).isEqualTo("application/x-protobuf");
    assertThat(headers.get("payload_type").asText()).isEqualTo(record.payloadType());
    assertThat(payload.getMetadata().getEventId()).isEqualTo(record.eventId());
    assertThat(payload.getMetadata().getCreatedAtUnixMs()).isEqualTo(100L);
    assertThat(payload.getMetadata().getSourceService()).isEqualTo("risk-service");
    assertThat(payload.getCommandId()).isEqualTo("cmd-1");
    assertThat(payload.getOrderId()).isEqualTo("O-C1");
    assertThat(payload.getAccountId()).isEqualTo("ACC-1");
    assertThat(payload.getSymbol()).isEqualTo("AAPL");
    assertThat(payload.getRoutingPartition()).isEqualTo("7");
  }

  @Test
  void createsRejectedOutboxRecord() throws Exception {
    final SubmissionDecision decision = rejectedDecision();

    final OutboxRecord record = factory.create(decision);
    final OrderRejected payload = OrderRejected.parseFrom(record.payload());
    final JsonNode headers = objectMapper.readTree(record.headersJson());

    assertThat(record.messageKey()).isEqualTo("AAPL");
    assertThat(record.payloadType()).isEqualTo(OrderRejected.getDescriptor().getFullName());
    assertThat(record.eventId()).isEqualTo(expectedEventId(decision.submission()));
    assertThat(headers.get("event_id").asText()).isEqualTo(record.eventId());
    assertThat(headers.get("payload_type").asText()).isEqualTo(record.payloadType());
    assertThat(payload.getCommandId()).isEqualTo("cmd-1");
    assertThat(payload.getOrderId()).isEqualTo("O-C1");
    assertThat(payload.getAccountId()).isEqualTo("ACC-1");
    assertThat(payload.getSymbol()).isEqualTo("AAPL");
    assertThat(payload.getRejectReasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(payload.getRejectReasonText()).isEqualTo("price is required for limit orders");
  }

  @Test
  void fallsBackToOrderIdWhenSymbolIsMissing() {
    final SubmissionCommand command = cancelOrderPayload("cmd-2", "O-C1", "CXL-1", "C1");
    final SubmissionDecision decision = new SubmissionDecision(
        new SubmissionResult(
            "COMMAND_TYPE_CANCEL|CXL-1",
            "cmd-2",
            "O-C1",
            "CXL-1",
            "C1",
            CommandType.COMMAND_TYPE_CANCEL,
            true,
            "",
            "",
            101L),
          new ResolvedSubmissionCommand(command, CommandType.COMMAND_TYPE_CANCEL));

    final OutboxRecord record = factory.create(decision);

    assertThat(record.messageKey()).isEqualTo("O-C1");
  }

  @Test
  void fallsBackToUnknownWhenSymbolAndOrderIdAreMissing() {
    final SubmissionDecision decision = new SubmissionDecision(
        new SubmissionResult(
            "UNKNOWN|",
            "",
            "",
            "",
            "",
            CommandType.COMMAND_TYPE_UNSPECIFIED,
            false,
            "EMPTY_COMMAND",
            "risk command payload is required",
            102L),
          ResolvedSubmissionCommand.unspecified());

    final OutboxRecord record = factory.create(decision);

    assertThat(record.messageKey()).isEqualTo("UNKNOWN");
  }

  @Test
  void createsStableEventIdForEquivalentSubmission() {
    final SubmissionDecision decision = acceptedDecision();

    final OutboxRecord first = factory.create(decision);
    final OutboxRecord second = factory.create(decision);

    assertThat(first.eventId()).isEqualTo(second.eventId());
  }

  @Test
  void wrapsHeaderSerializationFailures() {
    final SubmissionOutboxFactory failingFactory = new SubmissionOutboxFactory(
      failingObjectMapper(),
      "orders.validated",
      symbol -> 7);

    assertThatThrownBy(() -> failingFactory.create(acceptedDecision()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to serialize outbox headers");
  }

  private SubmissionDecision acceptedDecision() {
    return new SubmissionDecision(
        new SubmissionResult(
            "COMMAND_TYPE_NEW|C1",
            "cmd-1",
            "O-C1",
            "C1",
            "",
            CommandType.COMMAND_TYPE_NEW,
            true,
            "",
            "",
            100L),
          resolvedNewOrder("cmd-1", "O-C1", "C1"));
  }

  private SubmissionDecision rejectedDecision() {
    final SubmissionCommand command = newOrderPayload(
        "cmd-1",
        "O-C1",
        "C1",
        "",
        OrderType.ORDER_TYPE_LIMIT);
    return new SubmissionDecision(
        new SubmissionResult(
            "COMMAND_TYPE_NEW|C1",
            "cmd-1",
            "O-C1",
            "C1",
            "",
            CommandType.COMMAND_TYPE_NEW,
            false,
            "MISSING_PRICE",
            "price is required for limit orders",
            100L),
          new ResolvedSubmissionCommand(command, CommandType.COMMAND_TYPE_NEW));
  }

  private String expectedEventId(SubmissionResult submission) {
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