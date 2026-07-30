package com.simplematch.riskservice.outbox;

import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.OrderType;
import com.simplematch.riskservice.submission.ResolvedSubmissionCommand;
import com.simplematch.riskservice.submission.Side;
import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionDecision;
import com.simplematch.riskservice.submission.SubmissionResult;
import com.simplematch.riskservice.submission.TimeInForce;
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
    final OrderValidated payload = OrderValidated.parseFrom(record.payload());
    final JsonNode headers = objectMapper.readTree(record.headersJson());

    assertThat(record.topic()).isEqualTo("orders.validated");
    assertThat(record.messageKey()).isEqualTo("AAPL");
    assertThat(record.kafkaPartitionId()).isEqualTo(7);
    assertThat(record.payloadType()).isEqualTo(OrderValidated.getDescriptor().getFullName());
    assertThat(record.aggregateType()).isEqualTo("risk_submission");
    assertThat(record.aggregateId()).isEqualTo("O-C1");
    assertThat(record.createdAtUnixMs()).isEqualTo(100L);
    assertUuidVersionSeven(record.eventId());
    assertThat(headers.get("event_id").asText()).isEqualTo(record.eventId());
    assertThat(headers.get("content_type").asText()).isEqualTo("application/x-protobuf");
    assertThat(headers.get("payload_type").asText()).isEqualTo(record.payloadType());
    assertThat(payload.getMetadata().getEventId()).isEqualTo(record.eventId());
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
    final OrderRejected payload = OrderRejected.parseFrom(record.payload());
    final JsonNode headers = objectMapper.readTree(record.headersJson());

    assertThat(record.messageKey()).isEqualTo("AAPL");
    assertThat(record.payloadType()).isEqualTo(OrderRejected.getDescriptor().getFullName());
    assertUuidVersionSeven(record.eventId());
    assertThat(headers.get("event_id").asText()).isEqualTo(record.eventId());
    assertThat(headers.get("payload_type").asText()).isEqualTo(record.payloadType());
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
            new SubmissionResult(
                normalize("cmd-2"),
                command.senderCompId(),
                command.targetCompId(),
                java.time.LocalDate.of(2024, 3, 27),
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
    final SubmissionDecision decision =
        new SubmissionDecision(
            new SubmissionResult(
                "",
                "",
                "",
                java.time.LocalDate.of(1970, 1, 1),
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
  void createsDistinctEventIdsForEquivalentSubmission() {
    final SubmissionDecision decision = acceptedDecision();

    final OutboxRecord first = factory.create(decision);
    final OutboxRecord second = factory.create(decision);

    assertThat(first.eventId()).isNotEqualTo(second.eventId());
    assertUuidVersionSeven(first.eventId());
    assertUuidVersionSeven(second.eventId());
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
        new SubmissionResult(
            normalize("cmd-1"),
            "CLIENT",
            "SIMPLEMATCH",
            java.time.LocalDate.of(2024, 3, 27),
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
    final SubmissionCommand command =
        newOrderPayload("cmd-1", "O-C1", "C1", "", OrderType.ORDER_TYPE_LIMIT);
    return new SubmissionDecision(
        new SubmissionResult(
            normalize("cmd-1"),
            command.senderCompId(),
            command.targetCompId(),
            java.time.LocalDate.of(2024, 3, 27),
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

  private SubmissionCommand newOrderPayload(
      String commandId, String orderId, String clOrdId, String price, OrderType orderType) {
    return SubmissionCommand.create(
        requestMetadata(commandId, orderId, clOrdId, ""),
        new SubmissionCommand.OrderDetails(
            "AAPL", Side.SIDE_BUY, "10", price, orderType, TimeInForce.TIME_IN_FORCE_ROD));
  }

  private SubmissionCommand cancelOrderPayload(
      String commandId, String orderId, String clOrdId, String origClOrdId) {
    return SubmissionCommand.create(
        requestMetadata(commandId, orderId, clOrdId, origClOrdId),
        SubmissionCommand.OrderDetails.empty());
  }

  private ResolvedSubmissionCommand resolvedNewOrder(
      String commandId, String orderId, String clOrdId) {
    return new ResolvedSubmissionCommand(
        newOrderPayload(commandId, orderId, clOrdId, "101.25", OrderType.ORDER_TYPE_LIMIT),
        CommandType.COMMAND_TYPE_NEW);
  }

  private SubmissionCommand.RequestMetadata requestMetadata(
      String commandId, String orderId, String clOrdId, String origClOrdId) {
    return new SubmissionCommand.RequestMetadata(
        normalize(commandId), orderId, "ACC-1", "CLIENT", "SIMPLEMATCH", clOrdId, origClOrdId);
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
