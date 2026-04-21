package com.simplematch.riskservice.store;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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

class PostgresSubmissionStoreTest {
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private ObjectMapper objectMapper;
  private PostgresSubmissionStore store;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
    store = new PostgresSubmissionStore(
        jdbcTemplate,
        transactionTemplate,
        objectMapper,
        "orders.validated");
  }

  @Test
  void persistsAcceptedSubmissionAndReusesItForDuplicateIdempotencyKey() {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final StoredSubmission first = store.persist(command);
    final StoredSubmission duplicate = store.persist(command.toBuilder().setCommandId("cmd-2").build());

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidNewOrderCases")
  void rejectsInvalidNewOrdersWithSpecificReasonCodes(
      String ignoredCaseName,
      OrderCommand command,
      String expectedReasonCode) {
    final StoredSubmission submission = store.persist(command);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo(expectedReasonCode);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
    assertThat(countRows("outbox")).isEqualTo(1);
  }

  @Test
  void rejectsIncompleteLimitOrderAndPersistsRejectionForDuplicateRequests() {
    final OrderCommand invalid = newNewOrder("cmd-1", "O-C1", "C1").toBuilder().clearPrice().build();

    final StoredSubmission first = store.persist(invalid);
    final StoredSubmission duplicate = store.persist(invalid.toBuilder().setCommandId("cmd-2").build());

    assertThat(first.accepted()).isFalse();
    assertThat(first.reasonCode()).isEqualTo("MISSING_PRICE");
    assertThat(duplicate).isEqualTo(first);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
  }

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

    final StoredSubmission submission = store.persist(cancel);

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.reasonCode()).isEqualTo("MISSING_ORIGINAL_CLIENT_ORDER_ID");
  }

  @Test
  void rejectsEmptyCommandAndWritesRejectedOutboxEvent() throws Exception {
    final StoredSubmission first = store.persist(null);
    final StoredSubmission duplicate = store.persist(null);
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

  @Test
  void acceptsMarketOrderWithoutPrice() {
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", "C1")
        .setOrderType(OrderType.ORDER_TYPE_MARKET)
        .clearPrice()
        .build();

    final StoredSubmission submission = store.persist(command);

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.reasonCode()).isEmpty();
    assertThat(submission.reasonText()).isEmpty();
  }

  @Test
  void acceptsValidCancelAndUsesOrderIdAsMessageKeyWhenSymbolIsMissing() throws Exception {
    final OrderCommand cancel = newCancelOrderBuilder("cmd-1", "O-C1", "CXL-1", "C1")
        .build();

    final StoredSubmission submission = store.persist(cancel);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(submission.idempotencyKey());
    final OrderValidated validated = OrderValidated.parseFrom(outboxRow.payload());

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.commandType()).isEqualTo("COMMAND_TYPE_CANCEL");
    assertThat(outboxRow.messageKey()).isEqualTo(submission.orderId());
    assertThat(outboxRow.payloadType()).isEqualTo(OrderValidated.getDescriptor().getFullName());
    assertThat(validated.getOrderId()).isEqualTo(submission.orderId());
    assertThat(validated.getCommandId()).isEqualTo(submission.requestId());
  }

  @Test
  void persistsAcceptedOutboxPayloadContract() throws Exception {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final StoredSubmission submission = store.persist(command);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(submission.idempotencyKey());
    final OrderValidated validated = OrderValidated.parseFrom(outboxRow.payload());
    final JsonNode headers = objectMapper.readTree(outboxRow.headersJson());

    assertThat(outboxRow.topic()).isEqualTo("orders.validated");
    assertThat(outboxRow.messageKey()).isEqualTo(command.getSymbol());
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
  }

  @Test
  void persistsRejectedOutboxPayloadContract() throws Exception {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1").toBuilder().clearPrice().build();

    final StoredSubmission submission = store.persist(command);
    final OutboxRow outboxRow = outboxRowForIdempotencyKey(submission.idempotencyKey());
    final OrderRejected rejected = OrderRejected.parseFrom(outboxRow.payload());
    final JsonNode headers = objectMapper.readTree(outboxRow.headersJson());

    assertThat(submission.accepted()).isFalse();
    assertThat(outboxRow.topic()).isEqualTo("orders.validated");
    assertThat(outboxRow.messageKey()).isEqualTo(command.getSymbol());
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

  @Test
  void rollsBackSubmissionWhenOutboxSerializationFails() {
    final PostgresSubmissionStore failingStore = new PostgresSubmissionStore(
        jdbcTemplate,
        transactionTemplate,
        failingObjectMapper(),
        "orders.validated");

    assertThatThrownBy(() -> failingStore.persist(newNewOrder("cmd-1", "O-C1", "C1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to serialize outbox headers");
    assertThat(countRows("risk_submissions")).isZero();
    assertThat(countRows("outbox")).isZero();
  }

  @Test
  void returnsExistingSubmissionWhenConcurrentInsertCausesDuplicateKey() throws Exception {
    final OrderCommand delayedCommand = newNewOrder("cmd-delayed", "O-C1", "C1");
    final OrderCommand winnerCommand = delayedCommand.toBuilder().setCommandId("cmd-winner").build();
    final CountDownLatch firstLookupCompleted = new CountDownLatch(1);
    final CountDownLatch allowDelayedInsert = new CountDownLatch(1);
    final PostgresSubmissionStore delayedStore = new PostgresSubmissionStore(
        new BlockingJdbcTemplate(
            jdbcTemplate,
            delayedCommand.getCommandType().name() + "|" + delayedCommand.getClientOrderId(),
            firstLookupCompleted,
            allowDelayedInsert),
        transactionTemplate,
        objectMapper,
        "orders.validated");
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      final Future<StoredSubmission> delayedFuture = executor.submit(() -> delayedStore.persist(delayedCommand));

      assertThat(firstLookupCompleted.await(5, TimeUnit.SECONDS)).isTrue();

      final StoredSubmission winner = store.persist(winnerCommand);
      allowDelayedInsert.countDown();
      final StoredSubmission delayedResult = delayedFuture.get(5, TimeUnit.SECONDS);

      assertThat(delayedResult).isEqualTo(winner);
      assertThat(delayedResult.requestId()).isEqualTo("cmd-winner");
      assertThat(countRows("risk_submissions")).isEqualTo(1);
      assertThat(countRows("outbox")).isEqualTo(1);
    } finally {
      allowDelayedInsert.countDown();
      executor.shutdownNow();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("oversizedCommandCases")
  void rollsBackWhenRequiredColumnsExceedDatabaseLength(String ignoredCaseName, OrderCommand command) {
    assertThatThrownBy(() -> store.persist(command)).isInstanceOf(DataAccessException.class);
    assertThat(countRows("risk_submissions")).isZero();
    assertThat(countRows("outbox")).isZero();
  }

  @Test
  void storesSpecialCharactersAsDataWithoutBreakingPersistence() {
    final String clientOrderId = "C1';DROP TABLE outbox;--" + "-\u6e2c\u8a66-\uD83D\uDE80";
    final String symbol = "AAPL' OR '1'='1" + "-\u00DF";
    final OrderCommand command = newNewOrderBuilder("cmd-1", "O-C1", clientOrderId)
        .setSymbol(symbol)
        .build();

    final StoredSubmission submission = store.persist(command);

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

  @Test
  void keepsOutboxEventIdStableForDuplicateSubmissions() {
    final OrderCommand command = newNewOrder("cmd-1", "O-C1", "C1");

    final StoredSubmission first = store.persist(command);
    final StoredSubmission duplicate = store.persist(command.toBuilder().setCommandId("cmd-2").build());
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
        SELECT event_id, topic, message_key, payload, payload_type, headers_json,
               aggregate_type, aggregate_id, created_at_unix_ms
        FROM outbox
        WHERE event_id = ?
        """,
        (resultSet, rowNum) -> new OutboxRow(
            resultSet.getString("event_id"),
            resultSet.getString("topic"),
            resultSet.getString("message_key"),
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

  private String expectedOutboxEventId(StoredSubmission submission) {
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
          && sql.contains("FROM risk_submissions")
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

  private record OutboxRow(
      String eventId,
      String topic,
      String messageKey,
      byte[] payload,
      String payloadType,
      String headersJson,
      String aggregateType,
      String aggregateId,
      long createdAtUnixMs) {
  }
}