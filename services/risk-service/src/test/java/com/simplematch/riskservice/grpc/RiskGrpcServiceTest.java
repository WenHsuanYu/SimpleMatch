package com.simplematch.riskservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.risk.v1.CancelOrderRequest;
import com.simplematch.contracts.risk.v1.CancelOrderResponse;
import com.simplematch.contracts.risk.v1.SubmitOrderRequest;
import com.simplematch.contracts.risk.v1.SubmitOrderResponse;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import com.simplematch.riskservice.store.JdbcSubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionIdempotencyKeyFactory;
import com.simplematch.riskservice.submission.SubmissionOutboxFactory;
import com.simplematch.riskservice.submission.SubmissionService;
import com.simplematch.riskservice.submission.SubmissionValidator;
import com.simplematch.riskservice.submission.TransactionalSubmissionService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RiskGrpcServiceTest {
  private static final long GATEWAY_CREATED_AT_UNIX_MS = 1711526950123L;

  private JdbcTemplate jdbcTemplate;
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
    Flyway.configure()
      .baselineOnMigrate(true)
      .baselineVersion("1")
      .dataSource(dataSource)
      .locations("classpath:db/migration/risk-service")
      .load()
      .migrate();
    submissionService = new TransactionalSubmissionService(
        new SubmissionIdempotencyKeyFactory(),
        new SubmissionValidator(Clock.systemUTC()),
        new SubmissionOutboxFactory(new ObjectMapper(), "orders.validated"),
        new JdbcSubmissionRepository(jdbcTemplate),
        new JdbcOutboxRepository(jdbcTemplate),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

    // Verify that submitOrder returns an accepted gRPC response after persistence succeeds.
    // Scenario: submit a valid new order and confirm the observer receives completion and the expected order data.
    @DisplayName("submitOrder returns an accepted response after persistence")
  @Test
  void submitOrderReturnsAcceptedResponseAfterPersistence() {
      final RiskGrpcService service = new RiskGrpcService(submissionService);
    final TestStreamObserver<SubmitOrderResponse> observer = new TestStreamObserver<>();

    service.submitOrder(
        SubmitOrderRequest.newBuilder().setCommand(newNewOrder("cmd-1", "O-C1", "C1")).build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isTrue();
    assertThat(observer.value().getOrderId()).isEqualTo("O-C1");
    assertThat(observer.value().getClientOrderId()).isEqualTo("C1");
    assertThat(jdbcTemplate.queryForObject(
      "SELECT session_id FROM risk_submissions WHERE client_order_id = ?",
      String.class,
      "C1")).isEqualTo("FIX.4.4:CLIENT->SIMPLEMATCH");
    assertThat(jdbcTemplate.queryForObject(
      "SELECT trading_day FROM risk_submissions WHERE client_order_id = ?",
      LocalDate.class,
      "C1")).isEqualTo(LocalDate.of(2024, 3, 27));
  }

    // Verify that cancelOrder returns a rejection instead of a gRPC error when originalClientOrderId is missing.
    // Scenario: submit an incomplete cancel order and confirm the response reason code matches the validation rule.
    @DisplayName("cancelOrder returns rejected when the original client order id is missing")
  @Test
  void cancelOrderReturnsRejectedResponseWhenOriginalClientOrderIdIsMissing() {
      final RiskGrpcService service = new RiskGrpcService(submissionService);
    final TestStreamObserver<CancelOrderResponse> observer = new TestStreamObserver<>();

    service.cancelOrder(
        CancelOrderRequest.newBuilder()
            .setCommand(OrderCommand.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                    .setSchemaVersion("v1")
                    .setEventId("cmd-2")
                    .setCreatedAtUnixMs(GATEWAY_CREATED_AT_UNIX_MS)
                    .setSourceService("quickfix-gateway")
                    .build())
                .setCommandId("cmd-2")
                .setOrderId("O-C1")
                .setClientOrderId("CXL-1")
                .build())
            .build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isFalse();
    assertThat(observer.value().getReasonCode()).isEqualTo("MISSING_ORIGINAL_CLIENT_ORDER_ID");
  }

    // Verify that submitOrder normalizes an unexpected command type to NEW before persistence.
    // Scenario: send a new order whose command type was mistakenly set to CANCEL and confirm the database stores NEW.
    @DisplayName("submitOrder normalizes unexpected command type to NEW")
  @Test
  void submitOrderNormalizesUnexpectedCommandTypeToNew() {
      final RiskGrpcService service = new RiskGrpcService(submissionService);
    final TestStreamObserver<SubmitOrderResponse> observer = new TestStreamObserver<>();

    service.submitOrder(
        SubmitOrderRequest.newBuilder()
            .setCommand(newNewOrder("cmd-3", "O-C2", "C2").toBuilder()
                .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
                .clearOriginalClientOrderId()
                .build())
            .build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT command_type FROM risk_submissions WHERE client_order_id = ?",
        String.class,
        "C2")).isEqualTo("COMMAND_TYPE_NEW");
  }

    // Verify that cancelOrder normalizes an unexpected command type to CANCEL before persistence.
    // Scenario: send a cancel order whose command type was mistakenly set to NEW and confirm the database stores CANCEL.
    @DisplayName("cancelOrder normalizes unexpected command type to CANCEL")
  @Test
  void cancelOrderNormalizesUnexpectedCommandTypeToCancel() {
      final RiskGrpcService service = new RiskGrpcService(submissionService);
    final TestStreamObserver<CancelOrderResponse> observer = new TestStreamObserver<>();

    service.cancelOrder(
        CancelOrderRequest.newBuilder()
            .setCommand(newCancelOrder("cmd-4", "O-C1", "CXL-1", "C1").toBuilder()
                .setCommandType(CommandType.COMMAND_TYPE_NEW)
                .build())
            .build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isTrue();
    assertThat(observer.value().getOriginalClientOrderId()).isEqualTo("C1");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT command_type FROM risk_submissions WHERE client_order_id = ?",
        String.class,
        "CXL-1")).isEqualTo("COMMAND_TYPE_CANCEL");
  }

    // Verify that submitOrder returns the expected validation rejection code when it receives the default empty command.
    // Scenario: pass OrderCommand.getDefaultInstance() directly and confirm the result is rejected rather than an exception.
    @DisplayName("submitOrder returns the expected rejection code for the default empty command")
  @Test
  void submitOrderRejectsDefaultCommandInstanceWithExpectedReason() {
      final RiskGrpcService service = new RiskGrpcService(submissionService);
    final TestStreamObserver<SubmitOrderResponse> observer = new TestStreamObserver<>();

    service.submitOrder(
        SubmitOrderRequest.newBuilder().setCommand(OrderCommand.getDefaultInstance()).build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isFalse();
    assertThat(observer.value().getReasonCode()).isEqualTo("MISSING_CLIENT_ORDER_ID");
  }

    // Verify that cancelOrder returns the expected validation rejection code when it receives the default empty command.
    // Scenario: pass OrderCommand.getDefaultInstance() directly and confirm the cancel flow also returns a consistent rejected result.
    @DisplayName("cancelOrder returns the expected rejection code for the default empty command")
  @Test
  void cancelOrderRejectsDefaultCommandInstanceWithExpectedReason() {
      final RiskGrpcService service = new RiskGrpcService(submissionService);
    final TestStreamObserver<CancelOrderResponse> observer = new TestStreamObserver<>();

    service.cancelOrder(
        CancelOrderRequest.newBuilder().setCommand(OrderCommand.getDefaultInstance()).build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isFalse();
    assertThat(observer.value().getReasonCode()).isEqualTo("MISSING_CLIENT_ORDER_ID");
  }

  private OrderCommand newNewOrder(String commandId, String orderId, String clientOrderId) {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId(commandId)
          .setCreatedAtUnixMs(GATEWAY_CREATED_AT_UNIX_MS)
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
        .setCommandType(CommandType.COMMAND_TYPE_NEW)
        .build();
  }

  private OrderCommand newCancelOrder(String commandId, String orderId, String clientOrderId, String originalClientOrderId) {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId(commandId)
          .setCreatedAtUnixMs(GATEWAY_CREATED_AT_UNIX_MS)
            .setSourceService("quickfix-gateway")
            .build())
        .setCommandId(commandId)
        .setOrderId(orderId)
        .setClientOrderId(clientOrderId)
        .setOriginalClientOrderId(originalClientOrderId)
        .setCommandType(CommandType.COMMAND_TYPE_CANCEL)
        .build();
  }
}