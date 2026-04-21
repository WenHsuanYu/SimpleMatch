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
import com.simplematch.riskservice.store.PostgresSubmissionStore;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RiskGrpcServiceTest {
  private JdbcTemplate jdbcTemplate;
  private PostgresSubmissionStore store;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    jdbcTemplate = new JdbcTemplate(dataSource);
    Flyway.configure()
      .baselineOnMigrate(true)
      .baselineVersion("1")
      .dataSource(dataSource)
      .locations("classpath:db/migration/risk-service")
      .load()
      .migrate();
    store = new PostgresSubmissionStore(
        jdbcTemplate,
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        new ObjectMapper(),
        "orders.validated");
  }

  @Test
  void submitOrderReturnsAcceptedResponseAfterPersistence() {
    final RiskGrpcService service = new RiskGrpcService(store);
    final TestStreamObserver<SubmitOrderResponse> observer = new TestStreamObserver<>();

    service.submitOrder(
        SubmitOrderRequest.newBuilder().setCommand(newNewOrder("cmd-1", "O-C1", "C1")).build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isTrue();
    assertThat(observer.value().getOrderId()).isEqualTo("O-C1");
    assertThat(observer.value().getClientOrderId()).isEqualTo("C1");
  }

  @Test
  void cancelOrderReturnsRejectedResponseWhenOriginalClientOrderIdIsMissing() {
    final RiskGrpcService service = new RiskGrpcService(store);
    final TestStreamObserver<CancelOrderResponse> observer = new TestStreamObserver<>();

    service.cancelOrder(
        CancelOrderRequest.newBuilder()
            .setCommand(OrderCommand.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                    .setSchemaVersion("v1")
                    .setEventId("cmd-2")
                    .setCreatedAtUnixMs(1L)
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

  @Test
  void submitOrderNormalizesUnexpectedCommandTypeToNew() {
    final RiskGrpcService service = new RiskGrpcService(store);
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

  @Test
  void cancelOrderNormalizesUnexpectedCommandTypeToCancel() {
    final RiskGrpcService service = new RiskGrpcService(store);
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

  @Test
  void submitOrderRejectsDefaultCommandInstanceWithExpectedReason() {
    final RiskGrpcService service = new RiskGrpcService(store);
    final TestStreamObserver<SubmitOrderResponse> observer = new TestStreamObserver<>();

    service.submitOrder(
        SubmitOrderRequest.newBuilder().setCommand(OrderCommand.getDefaultInstance()).build(),
        observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getAccepted()).isFalse();
    assertThat(observer.value().getReasonCode()).isEqualTo("MISSING_CLIENT_ORDER_ID");
  }

  @Test
  void cancelOrderRejectsDefaultCommandInstanceWithExpectedReason() {
    final RiskGrpcService service = new RiskGrpcService(store);
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
        .setCommandType(CommandType.COMMAND_TYPE_NEW)
        .build();
  }

  private OrderCommand newCancelOrder(String commandId, String orderId, String clientOrderId, String originalClientOrderId) {
    return OrderCommand.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId(commandId)
            .setCreatedAtUnixMs(1L)
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