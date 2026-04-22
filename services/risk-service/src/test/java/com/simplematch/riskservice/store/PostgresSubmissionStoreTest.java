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

  // 驗證相同冪等鍵的重複送單會重用首次成功寫入的提交與 outbox 事件。
  // 情境：第二次送單只更換 commandId，但 clientOrderId 與指令型別相同。
  @DisplayName("重複冪等鍵的新單會重用既有成功提交")
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

  // 驗證新單缺少必要欄位時，風控層會依不同輸入給出對應的拒絕碼。
  // 情境：使用參數化測試覆蓋 clientOrderId、orderId、accountId、symbol、quantity、side、price 等缺漏案例。
  @DisplayName("新單缺少必要欄位時會依情境回傳對應拒絕碼")
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

  // 驗證不完整的限價單在第一次被拒絕後，後續重複請求仍會回傳同一筆拒絕結果。
  // 情境：限價單缺少 price，且第二次請求只更換 commandId。
  @DisplayName("不完整限價單的重複請求會重用既有拒絕結果")
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

  // 驗證取消單若缺少原始客戶單號，會被視為無效請求並拒絕。
  // 情境：建立一筆 CANCEL 指令，但不設定 originalClientOrderId。
  @DisplayName("取消單缺少原始客戶單號時會被拒絕")
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

  // 驗證空指令輸入會被拒絕，且仍會輸出可追蹤的 rejected outbox 事件。
  // 情境：連續兩次傳入 null，確認冪等去重與 rejected payload/header 契約都正確。
  @DisplayName("空指令會被拒絕並寫入 rejected outbox 事件")
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

  // 驗證市價單不需要 price 欄位即可通過風控驗證。
  // 情境：將原本的有效新單改成 MARKET 並移除 price。
  @DisplayName("市價單缺少價格時仍可通過驗證")
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

  // 驗證有效取消單在缺少 symbol 時，會退回使用 orderId 作為 outbox message key。
  // 情境：建立合法 CANCEL 指令，檢查持久化結果與 validated payload 內容。
  @DisplayName("有效取消單在缺少商品代碼時會使用 orderId 作為訊息鍵")
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

  // 驗證成功提交時寫出的 outbox payload、headers 與聚合欄位符合事件契約。
  // 情境：提交一筆有效新單，逐一比對 outbox 列與 OrderValidated protobuf 內容。
  @DisplayName("成功提交會寫出符合契約的 validated outbox payload")
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

  // 驗證拒絕提交時寫出的 outbox payload、headers 與拒絕原因符合事件契約。
  // 情境：送出缺少 price 的限價單，檢查 OrderRejected protobuf 與 outbox 欄位一致。
  @DisplayName("拒絕提交會寫出符合契約的 rejected outbox payload")
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

  // 驗證若 outbox headers 序列化失敗，整個交易會回滾，不留下半套資料。
  // 情境：使用刻意拋出例外的 ObjectMapper 建立 store，再嘗試持久化有效新單。
  @DisplayName("outbox 序列化失敗時會回滾整筆提交")
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

  // 驗證併發插入同一冪等鍵時，延後提交的執行緒會回讀勝出者的結果，而不是產生第二筆資料。
  // 情境：用阻塞查詢模擬 duplicate key race，確認最終只保留一筆 submission 與 outbox。
  @DisplayName("併發重複插入時會回傳既有提交而非重複寫入")
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

  // 驗證資料超過資料庫欄位長度限制時，整個交易會失敗且不留下任何殘留資料。
  // 情境：以參數化測試覆蓋 requestId、orderId、clientOrderId 等超長輸入。
  @DisplayName("必要欄位超過資料庫長度限制時會回滾")
  @ParameterizedTest(name = "{0}")
  @MethodSource("oversizedCommandCases")
  void rollsBackWhenRequiredColumnsExceedDatabaseLength(String ignoredCaseName, OrderCommand command) {
    assertThatThrownBy(() -> store.persist(command)).isInstanceOf(DataAccessException.class);
    assertThat(countRows("risk_submissions")).isZero();
    assertThat(countRows("outbox")).isZero();
  }

  // 驗證包含 SQL 特殊字元與 Unicode 的輸入會被當成純資料保存，而不會破壞持久化流程。
  // 情境：clientOrderId 與 symbol 含有引號、SQL 片段、德文字元與中文/emoji。
  @DisplayName("特殊字元輸入會被安全保存而不影響持久化")
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

  // 驗證重複送出同一冪等鍵時，既有 outbox event id 會保持穩定，不會因 requestId 改變而重算。
  // 情境：第二次送單更換 commandId，並檢查資料庫中的 outbox_event_id 是否仍等於首次結果。
  @DisplayName("重複提交不會改變既有 outbox event id")
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