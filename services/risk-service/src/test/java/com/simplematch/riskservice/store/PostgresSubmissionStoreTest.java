package com.simplematch.riskservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;

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
import com.simplematch.riskservice.outbox.SubmissionOutboxFactory;
import com.simplematch.riskservice.submission.ResolvedSubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionResult;
import com.simplematch.riskservice.submission.SubmissionService;
import com.simplematch.riskservice.submission.SubmissionValidator;
import com.simplematch.riskservice.submission.TransactionalSubmissionService;
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
      .dataSource(dataSource)
      .locations("classpath:db/migration/risk-service")
      .load()
      .migrate();
    submissionService = newSubmissionService(jdbcTemplate, objectMapper);
  }

  // Verify that duplicate submissions with the same business key reuse the first successfully stored submission and outbox event.
  // Scenario: the second submission changes only the commandId, while session, trading day, clientOrderId, and command type stay the same.
  @DisplayName("duplicate business keys reuse the existing successful submission")
  @Test
  void persistsAcceptedSubmissionAndReusesItForDuplicateBusinessKey() {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final SubmissionResult first = persist(command);
    final SubmissionResult duplicate = persist(command.toBuilder().setCommandId(normalize("cmd-2")).build());
    final String outboxEventId = storedOutboxEventId(first);

    assertThat(first.accepted()).isTrue();
    assertThat(first.requestId()).isEqualTo(command.getCommandId());
    assertThat(duplicate).isEqualTo(first);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_submissions", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT created_at_unix_ms FROM outbox WHERE event_id = ?",
      Long.class,
      UUID.fromString(outboxEventId))).isEqualTo(first.createdAtUnixMs());
    assertThat(jdbcTemplate.queryForObject(
      "SELECT topic FROM outbox WHERE event_id = ?",
      String.class,
      UUID.fromString(outboxEventId))).isEqualTo("orders.validated");
  }

    // Verify that the PostgreSQL-backed journal can store separate submissions when the FIX session changes and the business key differs.
    // Scenario: keep clientOrderId and command type unchanged, but change the FIX session so the composite business key differs.
    @DisplayName("different sessions produce separate submissions for the same client order id")
    @Test
    void persistsSeparateSubmissionsWhenOnlySessionDiffers() {
    final OrderCommand first = newNewOrder("cmd-1", "O-C1", "C1");
    final OrderCommand second = first.toBuilder()
      .setCommandId(normalize("cmd-2"))
      .setOrderId("O-C2")
      .setSenderCompId("CLIENT2")
      .setTargetCompId("SIMPLEMATCH")
      .setMetadata(first.getMetadata().toBuilder().setEventId(normalize("cmd-2")).build())
      .build();

    final SubmissionResult firstResult = persist(first);
    final SubmissionResult secondResult = persist(second);

    assertThat(firstResult).isNotEqualTo(secondResult);
    assertThat(countRows("risk_submissions")).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT COUNT(*) FROM risk_submissions WHERE cl_ord_id = ?",
      Integer.class,
      firstResult.clOrdId())).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT COUNT(*) FROM risk_submissions WHERE sender_comp_id = ? AND target_comp_id = ? AND cl_ord_id = ?",
      Integer.class,
      "CLIENT2",
      "SIMPLEMATCH",
      "C1")).isEqualTo(1);
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

  @DisplayName("missing session identity fields return the matching rejection code")
  @ParameterizedTest(name = "{0}")
  @MethodSource("missingSessionIdentityCases")
  void rejectsMissingSessionIdentityWithSpecificReasonCodes(
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
    final SubmissionResult duplicate = persist(invalid.toBuilder().setCommandId(normalize("cmd-2")).build());

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
        .setEventId(normalize("cmd-1"))
            .setCreatedAtUnixMs(1L)
            .setSourceService("quickfix-gateway")
            .build())
      .setCommandId(normalize("cmd-1"))
        .setOrderId("O-C1")
        .setSenderCompId("CLIENT")
        .setTargetCompId("SIMPLEMATCH")
        .setClOrdId("CXL-1")
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .build();

    final SubmissionResult submission = persist(cancel);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo("MISSING_ORIG_CL_ORD_ID");
  }

  // Verify that an empty command input is rejected and still emits a traceable rejected outbox event.
  // Scenario: pass null twice in a row and confirm the idempotent deduplication and rejected payload/header contract are both correct.
  @DisplayName("empty commands are rejected and written as rejected outbox events")
  @Test
  void rejectsEmptyCommandAndWritesRejectedOutboxEvent() throws Exception {
    final SubmissionResult first = persist((OrderCommand) null);
    final SubmissionResult duplicate = persist((OrderCommand) null);
    final OutboxRow outboxRow = outboxRowForSubmission(first);
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
    final OutboxRow outboxRow = outboxRowForSubmission(submission);
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
    final OutboxRow outboxRow = outboxRowForSubmission(submission);
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
    final OutboxRow outboxRow = outboxRowForSubmission(submission);
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

  // Verify that when concurrent inserts use the same business key, the delayed thread reads back the winner's result instead of creating a second record.
  // Scenario: use a blocking query to simulate a duplicate-key race and confirm only one submission and one outbox row remain.
  @DisplayName("concurrent duplicate inserts return the existing submission instead of writing another")
  @Test
  void returnsExistingSubmissionWhenConcurrentInsertCausesDuplicateKey() throws Exception {
    final OrderCommand delayedCommand = newNewOrder("cmd-delayed", "O-C1", "C1");
    final OrderCommand winnerCommand = delayedCommand.toBuilder().setCommandId(normalize("cmd-winner")).build();
    final CountDownLatch firstLookupCompleted = new CountDownLatch(1);
    final CountDownLatch allowDelayedInsert = new CountDownLatch(1);
    final SubmissionService delayedService = newSubmissionService(
      new BlockingJdbcTemplate(
        jdbcTemplate,
        delayedCommand.getSenderCompId(),
        delayedCommand.getTargetCompId(),
        java.time.LocalDate.now(java.time.Clock.systemUTC()),
        delayedCommand.getCommandType().name(),
        delayedCommand.getClOrdId(),
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
  assertThat(delayedResult.requestId()).isEqualTo(normalize("cmd-winner"));
      assertThat(countRows("risk_submissions")).isEqualTo(1);
      assertThat(countRows("outbox")).isEqualTo(1);
    } finally {
      allowDelayedInsert.countDown();
      executor.shutdownNow();
    }
  }

  // Verify that oversized request and order identifiers are rejected before persistence and still emit a rejected journal entry.
  // Scenario: keep the existing oversized inputs but expect validator-driven rejection instead of a JDBC failure.
  @DisplayName("oversized request and order identifiers are rejected before the database write")
  @ParameterizedTest(name = "{0}")
  @MethodSource("applicationValidatedOversizedCommandCases")
  void rejectsOversizedRequestOrOrderIdentifiersBeforePersistence(
      String ignoredCaseName,
      OrderCommand command,
      String expectedReasonCode) {
    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo(expectedReasonCode);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
  }

  @DisplayName("oversized session identity fields are rejected before the database write")
  @ParameterizedTest(name = "{0}")
  @MethodSource("oversizedSessionIdentityCases")
  void rejectsOversizedSessionIdentityBeforePersistence(
      String ignoredCaseName,
      OrderCommand command,
      String expectedReasonCode) {
    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo(expectedReasonCode);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
  }

  @DisplayName("oversized symbol is rejected before persistence and falls back to orderId for outbox message key")
  @Test
  void rejectsOversizedSymbolBeforePersistence() throws Exception {
    final String oversized = "X".repeat(300);
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", "C1")
        .setSymbol(oversized)
        .build();

    final SubmissionResult submission = persist(command);
    final OutboxRow outboxRow = outboxRowForSubmission(submission);
    final OrderRejected rejected = OrderRejected.parseFrom(outboxRow.payload());

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo("OVERSIZED_SYMBOL");
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
    assertThat(outboxRow.messageKey()).isEqualTo(command.getOrderId());
    assertThat(rejected.getSymbol()).isEqualTo(oversized);
  }

    // Verify that oversized ClOrdID is rejected before persistence while still storing the raw value separately from the dedup key.
    // Scenario: repeat the same oversized order twice and confirm the persisted business key stays deterministic.
    @DisplayName("oversized cl_ord_id is rejected before persistence and keeps raw data separately")
    @Test
    void rejectsOversizedClOrdIdBeforePersistenceAndKeepsRawData() {
    final String oversized = "X".repeat(300);
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", "C1")
      .setClOrdId(oversized)
      .build();

    final SubmissionResult first = persist(command);
    final SubmissionResult duplicate = persist(
      command.toBuilder()
        .setCommandId(normalize("cmd-2"))
        .setMetadata(command.getMetadata().toBuilder().setEventId(normalize("cmd-2")).build())
        .build());

    assertThat(first.accepted()).isFalse();
    assertThat(first.reasonCode()).isEqualTo("OVERSIZED_CL_ORD_ID");
    assertThat(first.clOrdId()).isEqualTo(oversized);
    assertThat(duplicate).isEqualTo(first);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT raw_cl_ord_id FROM risk_submissions WHERE request_id = ?",
      String.class,
      first.requestId())).isEqualTo(oversized);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT cl_ord_id FROM risk_submissions WHERE request_id = ?",
      String.class,
      first.requestId())).isEqualTo(first.persistedClOrdId());
    }

    // Verify that oversized OrigClOrdID is rejected before persistence while keeping the raw response value available.
    // Scenario: cancel rejects with the raw orig_cl_ord_id echoed back and a persistence-safe value stored separately.
    @DisplayName("oversized orig_cl_ord_id is rejected before persistence and keeps raw data separately")
    @Test
    void rejectsOversizedOrigClOrdIdBeforePersistenceAndKeepsRawData() {
    final String oversized = "X".repeat(300);
    final OrderCommand command = newCancelOrderBuilder("cmd-1", "O-C1", "CXL-1", "C1")
      .setOrigClOrdId(oversized)
      .build();

    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo("OVERSIZED_ORIG_CL_ORD_ID");
    assertThat(submission.origClOrdId()).isEqualTo(oversized);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT raw_orig_cl_ord_id FROM risk_submissions WHERE request_id = ?",
      String.class,
      submission.requestId())).isEqualTo(oversized);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT orig_cl_ord_id FROM risk_submissions WHERE request_id = ?",
      String.class,
      submission.requestId())).isEqualTo(submission.persistedOrigClOrdId());
    }

  // Verify that inputs containing SQL special characters and Unicode are stored as plain data without breaking persistence.
  // Scenario: clientOrderId and symbol contain quotes, SQL fragments, German characters, and Unicode/emoji.
  @DisplayName("special-character input is stored safely without affecting persistence")
  @Test
  void storesSpecialCharactersAsDataWithoutBreakingPersistence() {
    final String clientOrderId = "C1';DROP TABLE outbox;--" + "-test-\uD83D\uDE80";
    final String symbol = "AAPL' OR '1'='1" + "-\u00DF";
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", clientOrderId)
        .setSymbol(symbol)
        .build();

    final SubmissionResult submission = persist(command);

    assertThat(submission.accepted()).isTrue();
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT cl_ord_id FROM risk_submissions WHERE request_id = ?",
        String.class,
      submission.requestId())).isEqualTo(clientOrderId);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT message_key FROM outbox WHERE event_id = ?",
        String.class,
      UUID.fromString(storedOutboxEventId(submission)))).isEqualTo(symbol);
  }

    // Verify that when the same business key is submitted again, the existing outbox event id stays stable instead of being recalculated when the requestId changes.
  // Scenario: change the commandId on the second submission and confirm the stored outbox_event_id still matches the first result.
  @DisplayName("duplicate submissions do not change the existing outbox event id")
  @Test
  void keepsOutboxEventIdStableForDuplicateSubmissions() {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final SubmissionResult first = persist(command);
    final String firstStoredOutboxEventId = storedOutboxEventId(first);
    final SubmissionResult duplicate = persist(command.toBuilder().setCommandId(normalize("cmd-2")).build());
    final String duplicateStoredOutboxEventId = storedOutboxEventId(first);

    assertThat(duplicate).isEqualTo(first);
    assertThat(duplicateStoredOutboxEventId).isEqualTo(firstStoredOutboxEventId);
    assertUuidVersionSeven(duplicateStoredOutboxEventId);
  }

  private static Stream<Arguments> invalidNewOrderCases() {
    return Stream.of(
        Arguments.of(
          "missing cl_ord_id",
          newNewOrderBuilder("cmd-1", "O-C1", "C1").clearClOrdId().build(),
          "MISSING_CL_ORD_ID"),
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

      private static Stream<Arguments> missingSessionIdentityCases() {
      return Stream.of(
        Arguments.of(
          "missing sender_comp_id",
          newNewOrderBuilder("cmd-1", "O-C1", "C1")
            .clearSenderCompId()
            .build(),
          "MISSING_SENDER_COMP_ID"),
        Arguments.of(
          "missing target_comp_id",
          newNewOrderBuilder("cmd-1", "O-C1", "C1")
            .clearTargetCompId()
            .build(),
          "MISSING_TARGET_COMP_ID"));
      }

  private static Stream<Arguments> applicationValidatedOversizedCommandCases() {
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
                  .build(),
                "OVERSIZED_REQUEST_ID"),
        Arguments.of(
            "oversized order_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1")
                .setOrderId(oversized)
                  .build(),
                "OVERSIZED_ORDER_ID"));
            }

        private static Stream<Arguments> oversizedSessionIdentityCases() {
        final String oversized = "X".repeat(300);
        return Stream.of(
          Arguments.of(
            "oversized sender_comp_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1")
              .setSenderCompId(oversized)
              .build(),
            "OVERSIZED_SENDER_COMP_ID"),
          Arguments.of(
            "oversized target_comp_id",
            newNewOrderBuilder("cmd-1", "O-C1", "C1")
              .setTargetCompId(oversized)
              .build(),
            "OVERSIZED_TARGET_COMP_ID"));
        }

  private OrderCommand newNewOrder(String commandId, String orderId, String clientOrderId) {
    return newNewOrderBuilder(commandId, orderId, clientOrderId).build();
  }

  private SubmissionResult persist(OrderCommand command) {
    return submissionService.persist(toResolvedSubmissionCommand(command));
  }

  private SubmissionResult persist(SubmissionService service, OrderCommand command) {
    return service.persist(toResolvedSubmissionCommand(command));
  }

  private static ResolvedSubmissionCommand toResolvedSubmissionCommand(OrderCommand command) {
    if (command == null) {
      return null;
    }
    final SubmissionCommand payload = SubmissionCommand.create(
        new SubmissionCommand.RequestMetadata(
            command.getCommandId(),
            command.getOrderId(),
            command.getAccountId(),
        command.getSenderCompId(),
        command.getTargetCompId(),
        command.getClOrdId(),
        command.getOrigClOrdId()),
        new SubmissionCommand.OrderDetails(
            command.getSymbol(),
            toSubmissionSide(command.getSide()),
            command.getQuantity(),
            command.getPrice(),
            toSubmissionOrderType(command.getOrderType()),
            toSubmissionTimeInForce(command.getTif())));
    return new ResolvedSubmissionCommand(payload, toSubmissionCommandType(command.getCommandType()));
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
    final String normalizedCommandId = normalize(commandId);
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
        .setEventId(normalizedCommandId)
            .setCreatedAtUnixMs(1L)
            .setSourceService("quickfix-gateway")
            .build())
      .setCommandId(normalizedCommandId)
        .setOrderId(orderId)
        .setAccountId("ACC-1")
        .setSenderCompId("CLIENT")
        .setTargetCompId("SIMPLEMATCH")
        .setClOrdId(clientOrderId)
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
    final String normalizedCommandId = normalize(commandId);
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
        .setEventId(normalizedCommandId)
            .setCreatedAtUnixMs(1L)
            .setSourceService("quickfix-gateway")
            .build())
      .setCommandId(normalizedCommandId)
        .setOrderId(orderId)
        .setAccountId("ACC-1")
          .setSenderCompId("CLIENT")
          .setTargetCompId("SIMPLEMATCH")
          .setClOrdId(clientOrderId)
          .setOrigClOrdId(originalClientOrderId)
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

  private OutboxRow outboxRowForSubmission(SubmissionResult submission) {
    final String eventId = storedOutboxEventId(submission);
    return jdbcTemplate.queryForObject(
        """
        SELECT event_id, topic, message_key, kafka_partition_id, payload, payload_type, headers_json,
               aggregate_type, aggregate_id, created_at_unix_ms
        FROM outbox
        WHERE event_id = ?
        """,
        (resultSet, rowNum) -> new OutboxRow(
          resultSet.getObject("event_id", UUID.class).toString(),
            resultSet.getString("topic"),
            resultSet.getString("message_key"),
          resultSet.getObject("kafka_partition_id", Integer.class),
            resultSet.getBytes("payload"),
            resultSet.getString("payload_type"),
            resultSet.getString("headers_json"),
            resultSet.getString("aggregate_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getLong("created_at_unix_ms")),
        UUID.fromString(eventId));
  }

  private String storedOutboxEventId(SubmissionResult submission) {
    final var businessKey = submission.businessKey();
    return jdbcTemplate.queryForObject(
        """
        SELECT outbox_event_id
        FROM risk_submissions
        WHERE sender_comp_id = ?
          AND target_comp_id = ?
          AND trading_day = ?
          AND command_type = ?
          AND cl_ord_id = ?
          AND business_key_surrogated = ?
        """,
          UUID.class,
        businessKey.senderCompId(),
        businessKey.targetCompId(),
        businessKey.tradingDay(),
        businessKey.commandType().name(),
          businessKey.clOrdId(),
          businessKey.businessKeySurrogated()).toString();
  }

  private int countRows(String tableName) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
  }

  private void assertUuidVersionSeven(String rawUuid) {
    assertThat(UUID.fromString(rawUuid).version()).isEqualTo(7);
  }

  private SubmissionService newSubmissionService(JdbcTemplate submissionJdbcTemplate, ObjectMapper mapper) {
    return new TransactionalSubmissionService(
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
    private final String expectedSenderCompId;
    private final String expectedTargetCompId;
    private final java.time.LocalDate expectedTradingDay;
    private final String expectedCommandType;
    private final String expectedClOrdId;
    private final CountDownLatch firstLookupCompleted;
    private final CountDownLatch allowDelayedInsert;
    private boolean blocked;

    private BlockingJdbcTemplate(
        JdbcTemplate delegate,
        String expectedSenderCompId,
        String expectedTargetCompId,
        java.time.LocalDate expectedTradingDay,
        String expectedCommandType,
        String expectedClOrdId,
        CountDownLatch firstLookupCompleted,
        CountDownLatch allowDelayedInsert) {
      super(delegate.getDataSource());
      this.delegate = delegate;
      this.expectedSenderCompId = expectedSenderCompId;
      this.expectedTargetCompId = expectedTargetCompId;
      this.expectedTradingDay = expectedTradingDay;
      this.expectedCommandType = expectedCommandType;
      this.expectedClOrdId = expectedClOrdId;
      this.firstLookupCompleted = firstLookupCompleted;
      this.allowDelayedInsert = allowDelayedInsert;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
      final List<T> result = delegate.query(sql, rowMapper, args);
      if (!blocked
          && sql.contains("FROM risk_service.risk_submissions")
          && args.length == 6
          && expectedSenderCompId.equals(args[0])
          && expectedTargetCompId.equals(args[1])
          && expectedTradingDay.equals(args[2])
          && expectedCommandType.equals(args[3])
          && expectedClOrdId.equals(args[4])
          && Boolean.FALSE.equals(args[5])
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
