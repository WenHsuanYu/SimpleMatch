package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v1.OrderRejected;
import com.simplematch.contracts.orders.v1.OrderValidated;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import com.simplematch.riskservice.store.JdbcSubmissionRepository;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SubmissionServiceIntegrationTest {
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private ObjectMapper objectMapper;
  private SubmissionService submissionService;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
      "jdbc:h2:mem:"
        + UUID.randomUUID()
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service");
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    objectMapper = new ObjectMapper();
    Flyway.configure()
      .baselineOnMigrate(true)
      .baselineVersion("1")
      .dataSource(dataSource)
      .locations("classpath:db/migration/risk-service")
      .load()
      .migrate();
    submissionService = newSubmissionService(jdbcTemplate, objectMapper);
  }

  // Verify that duplicate submissions with the same idempotency key reuse the first successfully stored submission and outbox event.
  // Scenario: the second submission changes only the commandId, while clientOrderId and command type stay the same.
  @DisplayName("duplicate idempotency keys reuse the existing successful submission")
  @Test
  void persistsAcceptedSubmissionAndReusesItForDuplicateIdempotencyKey() {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final SubmissionResult first = persist(command);
    final SubmissionResult duplicate = persist(command.toBuilder().setCommandId("cmd-2").build());

    assertThat(first.accepted()).isTrue();
    assertThat(first.requestId()).isEqualTo("cmd-1");
    assertThat(duplicate).isEqualTo(first);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_submissions", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT created_at_unix_ms FROM outbox WHERE event_id = ?",
      Long.class,
      jdbcTemplate.queryForObject(
        "SELECT outbox_event_id FROM risk_submissions WHERE idempotency_key = ?",
        String.class,
        "COMMAND_TYPE_NEW|C1"))).isEqualTo(first.createdAtUnixMs());
    assertThat(jdbcTemplate.queryForObject(
      "SELECT topic FROM outbox WHERE event_id = ?",
      String.class,
      jdbcTemplate.queryForObject(
        "SELECT outbox_event_id FROM risk_submissions WHERE idempotency_key = ?",
        String.class,
        "COMMAND_TYPE_NEW|C1"))).isEqualTo("orders.validated");
  }

  // Verify that when a new order is missing required fields, the risk layer returns the corresponding rejection code for each input.
  // Scenario: use a parameterized test to cover missing clientOrderId, orderId, accountId, symbol, quantity, side, and price cases.
  @DisplayName("new orders missing required fields return the matching rejection code")
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidNewOrderCases")
  void rejectsInvalidNewOrdersWithSpecificReasonCodes(
      String ignoredCaseName,
      OrderCommand command,
      String expectedReasonCode) {
    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo(expectedReasonCode);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
  }

  // Verify that an incomplete limit order returns the same rejection result for later duplicate requests after the first rejection.
  // Scenario: the limit order is missing price, and the second request changes only the commandId.
  @DisplayName("duplicate requests for an incomplete limit order reuse the existing rejection result")
  @Test
  void rejectsIncompleteLimitOrderAndPersistsRejectionForDuplicateRequests() {
    final OrderCommand invalid = newNewOrder("cmd-1", "O-C1", "C1").toBuilder().clearPrice().build();

    final SubmissionResult first = persist(invalid);
    final SubmissionResult duplicate = persist(invalid.toBuilder().setCommandId("cmd-2").build());

    assertThat(first.accepted()).isFalse();
    assertThat(first.reasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(duplicate).isEqualTo(first);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
  }

  // Verify that a cancel order missing the original client order id is treated as an invalid request and rejected.
  // Scenario: build a CANCEL command without setting originalClientOrderId.
  @DisplayName("cancel orders are rejected when the original client order id is missing")
  @Test
  void rejectsCancelWithoutOriginalClientOrderId() {
    final OrderCommand cancel = OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId("cmd-1")
            .setCreatedAtUnixMs(1L)
            .setSourceService("quickfix-gateway")
            .build())
        .setCommandId("cmd-1")
        .setOrderId("O-C1")
        .setClientOrderId("CXL-1")
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .build();

    final SubmissionResult submission = persist(cancel);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo("MISSING_ORIGINAL_CLIENT_ORDER_ID");
  }

  // Verify that an empty command input is rejected and still emits a traceable rejected outbox event.
  // Scenario: pass null twice in a row and confirm the idempotent deduplication and rejected payload/header contract are both correct.
  @DisplayName("empty commands are rejected and written as rejected outbox events")
  @Test
  void rejectsEmptyCommandAndWritesRejectedOutboxEvent() throws Exception {
    final SubmissionResult first = persist((OrderCommand) null);
    final SubmissionResult duplicate = persist((OrderCommand) null);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(first.idempotencyKey());
    final OrderRejected rejected = OrderRejected.parseFrom(outboxRow.payload());
    final JsonNode headers = objectMapper.readTree(outboxRow.headersJson());

    assertThat(first.accepted()).isFalse();
    assertThat(first.reasonCode()).isEqualTo("EMPTY_COMMAND");
    assertThat(duplicate).isEqualTo(first);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
    assertThat(outboxRow.messageKey()).isEqualTo("UNKNOWN");
    assertThat(outboxRow.payloadType()).isEqualTo(OrderRejected.getDescriptor().getFullName());
    assertThat(headers.get("event_id").asText()).isEqualTo(outboxRow.eventId());
    assertThat(headers.get("payload_type").asText()).isEqualTo(outboxRow.payloadType());
    assertThat(rejected.getRejectReasonCode()).isEqualTo("EMPTY_COMMAND");
    assertThat(rejected.getRejectReasonText()).isEqualTo("risk command payload is required");
  }

  // Verify that a market order can pass risk validation without a price field.
  // Scenario: convert a valid new order to MARKET and remove the price.
  @DisplayName("market orders still pass validation without a price")
  @Test
  void acceptsMarketOrderWithoutPrice() {
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", "C1")
        .setOrderType(OrderType.ORDER_TYPE_MARKET)
        .clearPrice()
        .build();

    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.reasonCode()).isEmpty();
    assertThat(submission.reasonText()).isEmpty();
  }

  // Verify that a valid cancel order falls back to orderId as the outbox message key when symbol is missing.
  // Scenario: build a valid CANCEL command and inspect the persisted result and validated payload contents.
  @DisplayName("valid cancel orders use orderId as the message key when symbol is missing")
  @Test
  void acceptsValidCancelAndUsesOrderIdAsMessageKeyWhenSymbolIsMissing() throws Exception {
    final OrderCommand cancel = newCancelOrderBuilder("cmd-1", "O-C1", "CXL-1", "C1")
        .build();

    final SubmissionResult submission = persist(cancel);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(submission.idempotencyKey());
    final OrderValidated validated = OrderValidated.parseFrom(outboxRow.payload());

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.commandType())
      .isEqualTo(com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_CANCEL);
    assertThat(outboxRow.messageKey()).isEqualTo(submission.orderId());
    assertThat(outboxRow.payloadType()).isEqualTo(OrderValidated.getDescriptor().getFullName());
    assertThat(validated.getOrderId()).isEqualTo(submission.orderId());
    assertThat(validated.getCommandId()).isEqualTo(submission.requestId());
  }

  // Verify that the outbox payload, headers, and aggregate fields written on success match the event contract.
  // Scenario: submit a valid new order and compare the outbox row with the OrderValidated protobuf contents field by field.
  @DisplayName("successful submissions write a validated outbox payload that matches the contract")
  @Test
  void persistsAcceptedOutboxPayloadContract() throws Exception {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final SubmissionResult submission = persist(command);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(submission.idempotencyKey());
    final OrderValidated validated = OrderValidated.parseFrom(outboxRow.payload());
    final JsonNode headers = objectMapper.readTree(outboxRow.headersJson());

    assertThat(outboxRow.topic()).isEqualTo("orders.validated");
    assertThat(outboxRow.messageKey()).isEqualTo(command.getSymbol());
    assertThat(outboxRow.kafkaPartitionId()).isEqualTo(7);
    assertThat(outboxRow.payloadType()).isEqualTo(OrderValidated.getDescriptor().getFullName());
    assertThat(outboxRow.aggregateType()).isEqualTo("risk_submission");
    assertThat(outboxRow.aggregateId()).isEqualTo(submission.orderId());
    assertThat(outboxRow.createdAtUnixMs()).isEqualTo(submission.createdAtUnixMs());
    assertThat(headers.get("event_id").asText()).isEqualTo(outboxRow.eventId());
    assertThat(headers.get("content_type").asText()).isEqualTo("application/x-protobuf");
    assertThat(headers.get("payload_type").asText()).isEqualTo(outboxRow.payloadType());
    assertThat(validated.getMetadata().getEventId()).isEqualTo(outboxRow.eventId());
    assertThat(validated.getMetadata().getCreatedAtUnixMs()).isEqualTo(submission.createdAtUnixMs());
    assertThat(validated.getMetadata().getSourceService()).isEqualTo("risk-service");
    assertThat(validated.getCommandId()).isEqualTo(submission.requestId());
    assertThat(validated.getOrderId()).isEqualTo(submission.orderId());
    assertThat(validated.getAccountId()).isEqualTo(command.getAccountId());
    assertThat(validated.getSymbol()).isEqualTo(command.getSymbol());
    assertThat(validated.getRoutingPartition()).isEqualTo("7");
  }

  // Verify that the outbox payload, headers, and rejection reason written for rejected submissions match the event contract.
  // Scenario: submit a limit order without price and confirm the OrderRejected protobuf matches the outbox fields.
  @DisplayName("rejected submissions write a rejected outbox payload that matches the contract")
  @Test
  void persistsRejectedOutboxPayloadContract() throws Exception {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1").toBuilder().clearPrice().build();

    final SubmissionResult submission = persist(command);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(submission.idempotencyKey());
    final OrderRejected rejected = OrderRejected.parseFrom(outboxRow.payload());
    final JsonNode headers = objectMapper.readTree(outboxRow.headersJson());

    assertThat(submission.accepted()).isFalse();
    assertThat(outboxRow.topic()).isEqualTo("orders.validated");
    assertThat(outboxRow.messageKey()).isEqualTo(command.getSymbol());
    assertThat(outboxRow.kafkaPartitionId()).isEqualTo(7);
    assertThat(outboxRow.payloadType()).isEqualTo(OrderRejected.getDescriptor().getFullName());
    assertThat(outboxRow.aggregateType()).isEqualTo("risk_submission");
    assertThat(outboxRow.aggregateId()).isEqualTo(submission.orderId());
    assertThat(headers.get("event_id").asText()).isEqualTo(outboxRow.eventId());
    assertThat(headers.get("payload_type").asText()).isEqualTo(outboxRow.payloadType());
    assertThat(rejected.getMetadata().getEventId()).isEqualTo(outboxRow.eventId());
    assertThat(rejected.getCommandId()).isEqualTo(submission.requestId());
    assertThat(rejected.getOrderId()).isEqualTo(submission.orderId());
    assertThat(rejected.getAccountId()).isEqualTo(command.getAccountId());
    assertThat(rejected.getSymbol()).isEqualTo(command.getSymbol());
    assertThat(rejected.getRejectReasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(rejected.getRejectReasonText()).isEqualTo("price is required for limit orders");
  }

  // Verify that if outbox header serialization fails, the entire transaction rolls back and leaves no partial data behind.
  // Scenario: create the store with an ObjectMapper that intentionally throws, then try to persist a valid new order.
  @DisplayName("outbox serialization failures roll back the entire submission")
  @Test
  void rollsBackSubmissionWhenOutboxSerializationFails() {
    final SubmissionService failingService = newSubmissionService(jdbcTemplate, failingObjectMapper());

    assertThatThrownBy(() -> persist(failingService, newNewOrder("cmd-1", "O-C1", "C1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to serialize outbox headers");
    assertThat(countRows("risk_submissions")).isZero();
    assertThat(countRows("outbox")).isZero();
  }

  // Verify that when concurrent inserts use the same idempotency key, the delayed thread reads back the winner's result instead of creating a second record.
  // Scenario: use a blocking query to simulate a duplicate-key race and confirm only one submission and one outbox row remain.
  @DisplayName("concurrent duplicate inserts return the existing submission instead of writing another")
  @Test
  void returnsExistingSubmissionWhenConcurrentInsertCausesDuplicateKey() throws Exception {
    final OrderCommand delayedCommand = newNewOrder("cmd-delayed", "O-C1", "C1");
    final OrderCommand winnerCommand = delayedCommand.toBuilder().setCommandId("cmd-winner").build();
    final CountDownLatch firstLookupCompleted = new CountDownLatch(1);
    final CountDownLatch allowDelayedInsert = new CountDownLatch(1);
    final SubmissionService delayedService = newSubmissionService(
      new BlockingJdbcTemplate(
        jdbcTemplate,
        delayedCommand.getCommandType().name() + "|" + delayedCommand.getClientOrderId(),
        firstLookupCompleted,
        allowDelayedInsert),
      objectMapper);
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      final Future<SubmissionResult> delayedFuture = executor.submit(() -> persist(delayedService, delayedCommand));

      assertThat(firstLookupCompleted.await(5, TimeUnit.SECONDS)).isTrue();

      final SubmissionResult winner = persist(winnerCommand);
      allowDelayedInsert.countDown();
      final SubmissionResult delayedResult = delayedFuture.get(5, TimeUnit.SECONDS);

      assertThat(delayedResult).isEqualTo(winner);
      assertThat(delayedResult.requestId()).isEqualTo("cmd-winner");
      assertThat(countRows("risk_submissions")).isEqualTo(1);
      assertThat(countRows("outbox")).isEqualTo(1);
    } finally {
      allowDelayedInsert.countDown();
      executor.shutdownNow();
    }
  }

  // Verify that the full transaction fails and leaves no residual data when values exceed the database column length limits.
  // Scenario: use a parameterized test to cover oversized requestId, orderId, and clientOrderId inputs.
  @DisplayName("rolls back when required fields exceed database length limits")
  @ParameterizedTest(name = "{0}")
  @MethodSource("oversizedCommandCases")
  void rollsBackWhenRequiredColumnsExceedDatabaseLength(String ignoredCaseName, OrderCommand command) {
    assertThatThrownBy(() -> persist(command)).isInstanceOf(DataAccessException.class);
    assertThat(countRows("risk_submissions")).isZero();
    assertThat(countRows("outbox")).isZero();
  }

  // Verify that inputs containing SQL special characters and Unicode are stored as plain data without breaking persistence.
  // Scenario: clientOrderId and symbol contain quotes, SQL fragments, German characters, and Unicode/emoji.
  @DisplayName("special-character input is stored safely without affecting persistence")
  @Test
  void storesSpecialCharactersAsDataWithoutBreakingPersistence() {
    final String clientOrderId = "C1';DROP TABLE outbox;--" + "-\u6e2c\u8a66-\uD83D\uDE80";
    final String symbol = "AAPL' OR '1'='1" + "-\u00DF";
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", clientOrderId)
        .setSymbol(symbol)
        .build();

    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isTrue();
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT client_order_id FROM risk_submissions WHERE idempotency_key = ?",
        String.class,
        submission.idempotencyKey())).isEqualTo(clientOrderId);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT message_key FROM outbox WHERE event_id = ?",
        String.class,
        jdbcTemplate.queryForObject(
            "SELECT outbox_event_id FROM risk_submissions WHERE idempotency_key = ?",
            String.class,
            submission.idempotencyKey()))).isEqualTo(symbol);
  }

  // Verify that duplicate submissions with the same idempotency key keep the existing outbox event id stable instead of recalculating it when the requestId changes.
  // Scenario: resubmit the order with a different commandId and confirm the stored outbox_event_id still matches the first result.
  @DisplayName("duplicate submissions do not change the existing outbox event id")
  @Test
  void keepsOutboxEventIdStableForDuplicateSubmissions() {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final SubmissionResult first = persist(command);
    final SubmissionResult duplicate = persist(command.toBuilder().setCommandId("cmd-2").build());
    final String storedOutboxEventId = jdbcTemplate.queryForObject(
        "SELECT outbox_event_id FROM risk_submissions WHERE idempotency_key = ?",
        String.class,
        first.idempotencyKey());

    assertThat(duplicate).isEqualTo(first);
    assertThat(storedOutboxEventId).isEqualTo(expectedOutboxEventId(first));
  }

  private static Stream<Arguments> invalidNewOrderCases() {
    return Stream.of(
        Arguments.of(
            "missing client_order_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").clearClientOrderId().build(),
            "MISSING_CLIENT_ORDER_ID"),
        Arguments.of(
            "missing order_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").clearOrderId().build(),
            "MISSING_ORDER_ID"),
        Arguments.of(
            "missing account_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").clearAccountId().build(),
            "MISSING_ACCOUNT_ID"),
        Arguments.of(
            "missing symbol",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").clearSymbol().build(),
            "MISSING_SYMBOL"),
        Arguments.of(
            "missing quantity",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").clearQuantity().build(),
            "MISSING_QUANTITY"),
        Arguments.of(
            "missing side",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").setSide(Side.SIDE_UNSPECIFIED).build(),
            "MISSING_SIDE"),
        Arguments.of(
            "missing price for limit order",
            newNewOrderBuilder("cmd-1", "O-C1", "C1").clearPrice().build(),
            "MISSING_PRICE"));
  }

  private static Stream<Arguments> oversizedCommandCases() {
    final String oversized = "X".repeat(300);
    return Stream.of(
        Arguments.of(
            "oversized request_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1")
                .setCommandId(oversized)
                .setMetadata(EventMetadata.newBuilder()
                    .setSchemaVersion("v1")
                    .setEventId(oversized)
                    .setCreatedAtUnixMs(1L)
                    .setSourceService("quickfix-gateway")
                    .build())
                .build()),
        Arguments.of(
            "oversized order_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1")
                .setOrderId(oversized)
                .build()),
        Arguments.of(
            "oversized client_order_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1")
                .setClientOrderId(oversized)
                .build()));
  }

  private OrderCommand newNewOrder(String commandId, String orderId, String clientOrderId) {
    return newNewOrderBuilder(commandId, orderId, clientOrderId).build();
  }

  private SubmissionResult persist(OrderCommand command) {
    return submissionService.persist(toSubmissionCommand(command));
  }

  private SubmissionResult persist(SubmissionService service, OrderCommand command) {
    return service.persist(toSubmissionCommand(command));
  }

  private static SubmissionCommand toSubmissionCommand(OrderCommand command) {
    if (command == null) {
      return null;
    }
    return new SubmissionCommand(
        command.getCommandId(),
        command.getOrderId(),
        command.getAccountId(),
        command.getSessionId(),
        command.getClientOrderId(),
        command.getSymbol(),
        toSubmissionSide(command.getSide()),
        command.getQuantity(),
        command.getPrice(),
        toSubmissionOrderType(command.getOrderType()),
        toSubmissionTimeInForce(command.getTif()),
        toSubmissionCommandType(command.getCommandType()),
        command.getOriginalClientOrderId());
  }

  private static com.simplematch.riskservice.submission.Side toSubmissionSide(Side side) {
    if (side == null) {
      return com.simplematch.riskservice.submission.Side.SIDE_UNSPECIFIED;
    }
    return switch (side) {
      case SIDE_BUY -> com.simplematch.riskservice.submission.Side.SIDE_BUY;
      case SIDE_SELL -> com.simplematch.riskservice.submission.Side.SIDE_SELL;
      default -> com.simplematch.riskservice.submission.Side.SIDE_UNSPECIFIED;
    };
  }

  private static com.simplematch.riskservice.submission.OrderType toSubmissionOrderType(OrderType orderType) {
    if (orderType == null) {
      return com.simplematch.riskservice.submission.OrderType.ORDER_TYPE_UNSPECIFIED;
    }
    return switch (orderType) {
      case ORDER_TYPE_LIMIT -> com.simplematch.riskservice.submission.OrderType.ORDER_TYPE_LIMIT;
      case ORDER_TYPE_MARKET -> com.simplematch.riskservice.submission.OrderType.ORDER_TYPE_MARKET;
      default -> com.simplematch.riskservice.submission.OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  private static com.simplematch.riskservice.submission.TimeInForce toSubmissionTimeInForce(TimeInForce timeInForce) {
    if (timeInForce == null) {
      return com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    }
    return switch (timeInForce) {
      case TIME_IN_FORCE_ROD -> com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_ROD;
      case TIME_IN_FORCE_IOC -> com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_IOC;
      case TIME_IN_FORCE_FOK -> com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_FOK;
      default -> com.simplematch.riskservice.submission.TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }

  private static com.simplematch.riskservice.submission.CommandType toSubmissionCommandType(CommandType commandType) {
    if (commandType == null) {
      return com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_UNSPECIFIED;
    }
    return switch (commandType) {
      case COMMAND_TYPE_NEW -> com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_NEW;
      case COMMAND_TYPE_CANCEL -> com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_CANCEL;
      default -> com.simplematch.riskservice.submission.CommandType.COMMAND_TYPE_UNSPECIFIED;
    };
  }

  private static OrderCommand.Builder newNewOrderBuilder(String commandId, String orderId, String clientOrderId) {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId(commandId)
            .setCreatedAtUnixMs(1L)
            .setSourceService("quickfix-gateway")
            .build())
        .setCommandId(commandId)
        .setOrderId(orderId)
        .setAccountId("ACC-1")
        .setSessionId("FIX.4.4:CLIENT->SIMPLEMATCH")
        .setClientOrderId(clientOrderId)
        .setSymbol("AAPL")
        .setSide(Side.SIDE_BUY)
        .setQuantity("10")
        .setPrice("101.25")
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCommandType(CommandType.COMMAND_TYPE_NEW);
  }

  private static OrderCommand.Builder newCancelOrderBuilder(
      String commandId,
      String orderId,
      String clientOrderId,
      String originalClientOrderId) {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId(commandId)
            .setCreatedAtUnixMs(1L)
            .setSourceService("quickfix-gateway")
            .build())
        .setCommandId(commandId)
        .setOrderId(orderId)
        .setAccountId("ACC-1")
        .setClientOrderId(clientOrderId)
        .setOriginalClientOrderId(originalClientOrderId)
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL);
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

  private OutboxRow outboxRowForIdempotencyKey(String idempotencyKey) {
    final String eventId = jdbcTemplate.queryForObject(
        "SELECT outbox_event_id FROM risk_submissions WHERE idempotency_key = ?",
        String.class,
        idempotencyKey);
    return jdbcTemplate.queryForObject(
        """
        SELECT event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
               aggregate_type, aggregate_id, created_at_unix_ms
        FROM outbox
        WHERE event_id = ?
        """,
        (resultSet, rowNum) -> new OutboxRow(
            resultSet.getString("event_id"),
            resultSet.getString("topic"),
            resultSet.getString("message_key"),
          resultSet.getObject("kafka_partition_id", Integer.class),
            resultSet.getBytes("payload"),
            resultSet.getString("payload_type"),
            resultSet.getString("headers_json"),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getLong("created_at_unix_ms")),
        eventId);
  }

  private int countRows(String tableName) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
  }

  private String expectedOutboxEventId(SubmissionResult submission) {
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

  private SubmissionService newSubmissionService(JdbcTemplate submissionJdbcTemplate, ObjectMapper mapper) {
    return new TransactionalSubmissionService(
        new SubmissionIdempotencyKeyFactory(),
        new SubmissionValidator(java.time.Clock.systemUTC()),
        new SubmissionOutboxFactory(
            mapper,
            "orders.validated",
            symbol -> "AAPL".equals(symbol) ? 7 : 0),
        new JdbcSubmissionRepository(submissionJdbcTemplate),
        new JdbcOutboxRepository(submissionJdbcTemplate),
        transactionTemplate);
  }

  private static final class BlockingJdbcTemplate extends JdbcTemplate {
    private final JdbcTemplate delegate;
    private final String expectedIdempotencyKey;
    private final CountDownLatch firstLookupCompleted;
    private final CountDownLatch allowDelayedInsert;
    private boolean blocked;

    private BlockingJdbcTemplate(
        JdbcTemplate delegate,
        String expectedIdempotencyKey,
        CountDownLatch firstLookupCompleted,
        CountDownLatch allowDelayedInsert) {
      super(delegate.getDataSource());
      this.delegate = delegate;
      this.expectedIdempotencyKey = expectedIdempotencyKey;
      this.firstLookupCompleted = firstLookupCompleted;
      this.allowDelayedInsert = allowDelayedInsert;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
      final List<T> result = delegate.query(sql, rowMapper, args);
      if (!blocked
          && sql.contains("FROM risk_service.risk_submissions")
          && args.length == 1
          && expectedIdempotencyKey.equals(args[0])
          && result.isEmpty()) {
        blocked = true;
        firstLookupCompleted.countDown();
        try {
          if (!allowDelayedInsert.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for winner insert");
          }
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while simulating duplicate key race", interruptedException);
        }
      }
      return result;
    }

    @Override
    public int update(String sql, Object... args) throws DataAccessException {
      return delegate.update(sql, args);
    }
  }

  private static final class OutboxRow {
    private final String eventId;
    private final String topic;
    private final String messageKey;
    private final Integer kafkaPartitionId;
    private final byte[] payload;
    private final String payloadType;
    private final String headersJson;
    private final String aggregateType;
    private final String aggregateId;
    private final long createdAtUnixMs;

    private OutboxRow(
        String eventId,
        String topic,
        String messageKey,
      Integer kafkaPartitionId,
        byte[] payload,
        String payloadType,
        String headersJson,
        String aggregateType,
        String aggregateId,
        long createdAtUnixMs) {
      this.eventId = Objects.requireNonNull(eventId);
      this.topic = Objects.requireNonNull(topic);
      this.messageKey = Objects.requireNonNull(messageKey);
      this.kafkaPartitionId = kafkaPartitionId;
      this.payload = Arrays.copyOf(Objects.requireNonNull(payload), payload.length);
      this.payloadType = Objects.requireNonNull(payloadType);
      this.headersJson = Objects.requireNonNull(headersJson);
      this.aggregateType = Objects.requireNonNull(aggregateType);
      this.aggregateId = Objects.requireNonNull(aggregateId);
      this.createdAtUnixMs = createdAtUnixMs;
    }

    private String eventId() {
      return eventId;
    }

    private String topic() {
      return topic;
    }

    private String messageKey() {
      return messageKey;
    }

    private Integer kafkaPartitionId() {
      return kafkaPartitionId;
    }

    private byte[] payload() {
      return Arrays.copyOf(payload, payload.length);
    }

    private String payloadType() {
      return payloadType;
    }

    private String headersJson() {
      return headersJson;
    }

    private String aggregateType() {
      return aggregateType;
    }

    private String aggregateId() {
      return aggregateId;
    }

    private long createdAtUnixMs() {
      return createdAtUnixMs;
    }
  }
}