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
import org.junit.jupiter.api.DisplayName;
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

    // 驗證 submitOrder 在持久化成功後，會回傳 accepted 的 gRPC 回應內容。
    // 情境：提交一筆有效新單，確認 observer 收到完成訊號且回傳訂單資訊正確。
    @DisplayName("submitOrder 成功持久化後會回傳 accepted 回應")
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

    // 驗證 cancelOrder 缺少 originalClientOrderId 時，會回傳拒絕結果而非丟出 gRPC 錯誤。
    // 情境：提交欄位不完整的取消單，確認回應內的 reason code 符合驗證規則。
    @DisplayName("cancelOrder 缺少原始客戶單號時會回傳 rejected")
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

    // 驗證 submitOrder 會將非預期的 command type 正規化成 NEW 再持久化。
    // 情境：送入 command type 被誤設為 CANCEL 的新單，確認資料庫內最終仍記錄為 NEW。
    @DisplayName("submitOrder 會將非預期 command type 正規化為 NEW")
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

    // 驗證 cancelOrder 會將非預期的 command type 正規化成 CANCEL 再持久化。
    // 情境：送入 command type 被誤設為 NEW 的取消單，確認資料庫內最終仍記錄為 CANCEL。
    @DisplayName("cancelOrder 會將非預期 command type 正規化為 CANCEL")
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

    // 驗證 submitOrder 收到預設空指令時，會回傳預期的驗證拒絕碼。
    // 情境：直接傳入 OrderCommand.getDefaultInstance()，確認結果為 rejected 而非例外。
    @DisplayName("submitOrder 遇到預設空指令時會回傳預期拒絕碼")
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

    // 驗證 cancelOrder 收到預設空指令時，會回傳預期的驗證拒絕碼。
    // 情境：直接傳入 OrderCommand.getDefaultInstance()，確認取消流程也會回傳一致的 rejected 結果。
    @DisplayName("cancelOrder 遇到預設空指令時會回傳預期拒絕碼")
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