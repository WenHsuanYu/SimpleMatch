package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionFailure;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.NewOrderSingle;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.MsgType;
import quickfix.field.OrderQty;
import quickfix.field.OrdType;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;

class InboundFixMessageHandlerTest {
  @TempDir
  Path tempDir;

  private static final Instant FIXED_INSTANT = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

  // 驗證新單基線流程會完整寫入 WAL、發佈 OrderCommand、註冊 session 狀態，並回送 Pending New。
  // 情境：提交一筆合法限價新單，檢查 WAL、命令內容、registry 狀態與 FIX 回覆是否一致。
  @DisplayName("新單基線流程會寫入 WAL、發佈命令並回送 Pending New")
  @Test
  void newOrderBaselinePathWritesExactWalPublishesExactCommandRegistersStateAndSendsPendingNew() throws Exception {
    final WalAppender walAppender = new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(publisher.publish(any(OrderCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
      .thenReturn(new RiskSubmissionResult("O-C1", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final FixMessageMapper mapper = new FixMessageMapper(FIXED_CLOCK);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final InboundFixMessageHandler handler = new InboundFixMessageHandler(
        walAppender,
        publisher,
      riskSubmissionClient,
        sender,
        registry,
        mapper,
        FIXED_CLOCK);

    final NewOrderSingle order = newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1");
    final SessionID sessionId = new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH");
    handler.handle(order, sessionId);

    final List<WalRecord> walRecords = walAppender.readAll();
    assertThat(walRecords).hasSize(1);

    final WalRecord walRecord = walRecords.getFirst();
    assertThat(walRecord.schemaVersion()).isEqualTo("v1");
    assertThat(walRecord.recordId()).isNotBlank();
    assertThat(walRecord.createdAtUnixMs()).isEqualTo(FIXED_INSTANT.toEpochMilli());
    assertThat(walRecord.sourceService()).isEqualTo("quickfix-gateway");
    assertThat(walRecord.sessionId()).isEqualTo(sessionId.toString());
    assertThat(walRecord.messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
    assertThat(walRecord.orderId()).isEqualTo("O-C1");
    assertThat(walRecord.clientOrderId()).isEqualTo("C1");
    assertThat(walRecord.originalClientOrderId()).isEmpty();
    assertThat(walRecord.accountId()).isEqualTo("ACC-1");
    assertThat(walRecord.symbol()).isEqualTo("AAPL");
    assertThat(walRecord.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(walRecord.quantity()).isEqualTo("10");
    assertThat(walRecord.price()).isEqualTo("101.25");
    assertThat(walRecord.orderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(walRecord.tif()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(walRecord.commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(walRecord.rawFix()).contains("35=D").contains("11=C1").contains("55=AAPL").contains("38=10");

    final ArgumentCaptor<OrderCommand> commandCaptor = ArgumentCaptor.forClass(OrderCommand.class);
    verify(publisher).publish(commandCaptor.capture());
    final OrderCommand command = commandCaptor.getValue();
    assertThat(command.getMetadata().getSchemaVersion()).isEqualTo("v1");
    assertThat(command.getMetadata().getEventId()).isEqualTo(walRecord.recordId());
    assertThat(command.getMetadata().getCreatedAtUnixMs()).isEqualTo(FIXED_INSTANT.toEpochMilli());
    assertThat(command.getMetadata().getSourceService()).isEqualTo("quickfix-gateway");
    assertThat(command.getCommandId()).isEqualTo(walRecord.recordId());
    assertThat(command.getCommandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(command.getOrderId()).isEqualTo("O-C1");
    assertThat(command.getAccountId()).isEqualTo("ACC-1");
    assertThat(command.getSessionId()).isEqualTo(sessionId.toString());
    assertThat(command.getClientOrderId()).isEqualTo("C1");
    assertThat(command.getOriginalClientOrderId()).isEmpty();
    assertThat(command.getSymbol()).isEqualTo("AAPL");
    assertThat(command.getSide()).isEqualTo(Side.SIDE_BUY);
    assertThat(command.getQuantity()).isEqualTo("10");
    assertThat(command.getPrice()).isEqualTo("101.25");
    assertThat(command.getOrderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(command.getTif()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);

    final OrderSessionState sessionState = registry.find("O-C1").orElseThrow();
    assertThat(sessionState.sessionId()).isEqualTo(sessionId);
    assertThat(sessionState.orderId()).isEqualTo("O-C1");
    assertThat(sessionState.accountId()).isEqualTo("ACC-1");
    assertThat(sessionState.clientOrderId()).isEqualTo("C1");
    assertThat(sessionState.symbol()).isEqualTo("AAPL");
    assertThat(sessionState.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(sessionState.quantity()).isEqualTo("10");
    assertThat(sessionState.currentOrdStatus()).isEqualTo('A');
    assertThat(sessionState.lastCancelRequest()).isNull();

    final ArgumentCaptor<SessionID> sessionCaptor = ArgumentCaptor.forClass(SessionID.class);
    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(sessionCaptor.capture(), messageCaptor.capture());
    assertThat(sessionCaptor.getValue()).isEqualTo(sessionId);
    assertThat(FixMessageSnapshot.snapshot(messageCaptor.getValue(), 35, 37, 150, 39, 11, 55))
        .startsWith("35=8|37=O-C1|150=A|39=A|11=C1|55=AAPL");
    assertThat(FixMessageSnapshot.snapshot(
        messageCaptor.getValue(),
        MsgType.FIELD,
        37,
        17,
        150,
        39,
        54,
        151,
        14,
        6,
        11,
        55,
        60))
            .isEqualTo(
                "35=8|37=O-C1|17=E-"
                    + walRecord.recordId()
                    + "|150=A|39=A|54=1|151=10|14=0|6=0|11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z");
  }

  // 驗證取消支援不會污染後續新單的基線行為。
  // 情境：先送新單、再送取消、再送第二筆新單，確認 WAL 順序、registry 狀態與第二筆 Pending New 都正確。
  @DisplayName("取消流程不會影響後續新單基線流程")
  @Test
  void cancelSupportDoesNotAlterSubsequentNewOrderBaselinePath() throws Exception {
    final WalAppender walAppender = new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(publisher.publish(any(OrderCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
      .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    when(riskSubmissionClient.submitCancel(any(OrderCommand.class)))
      .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final InboundFixMessageHandler handler = new InboundFixMessageHandler(
        walAppender,
        publisher,
      riskSubmissionClient,
        sender,
        registry,
        new FixMessageMapper(FIXED_CLOCK),
        FIXED_CLOCK);

    final SessionID sessionId = new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH");
    handler.handle(newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"), sessionId);
    handler.handle(newCancelRequest("C1", "CXL-1", "ACC-1"), sessionId);
    handler.handle(newNewOrder("C2", "MSFT", '2', "20", "305.50", "ACC-2"), sessionId);

    final List<WalRecord> walRecords = walAppender.readAll();
    assertThat(walRecords).hasSize(3);
    assertThat(walRecords.get(0).messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
    assertThat(walRecords.get(1).messageType()).isEqualTo(OrderCancelRequest.MSGTYPE);
    assertThat(walRecords.get(2).messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
    assertThat(walRecords.get(2).orderId()).isEqualTo("O-C2");
    assertThat(walRecords.get(2).side()).isEqualTo(Side.SIDE_SELL);

    final OrderSessionState firstOrderState = registry.find("O-C1").orElseThrow();
    assertThat(firstOrderState.lastCancelRequest()).isNotNull();
    assertThat(firstOrderState.lastCancelRequest().cancelClientOrderId()).isEqualTo("CXL-1");
    assertThat(firstOrderState.lastCancelRequest().originalClientOrderId()).isEqualTo("C1");

    final OrderSessionState secondOrderState = registry.find("O-C2").orElseThrow();
    assertThat(secondOrderState.accountId()).isEqualTo("ACC-2");
    assertThat(secondOrderState.clientOrderId()).isEqualTo("C2");
    assertThat(secondOrderState.symbol()).isEqualTo("MSFT");
    assertThat(secondOrderState.side()).isEqualTo(Side.SIDE_SELL);
    assertThat(secondOrderState.quantity()).isEqualTo("20");
    assertThat(secondOrderState.currentOrdStatus()).isEqualTo('A');
    assertThat(secondOrderState.lastCancelRequest()).isNull();

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender, times(2)).send(any(SessionID.class), messageCaptor.capture());
    final Message secondPendingNew = messageCaptor.getAllValues().get(1);
    assertThat(FixMessageSnapshot.snapshot(
        secondPendingNew,
        MsgType.FIELD,
        37,
        150,
        39,
        54,
        151,
        14,
        6,
        11,
        55,
        60))
            .isEqualTo(
                "35=8|37=O-C2|150=A|39=A|54=2|151=20|14=0|6=0|11=C2|55=MSFT|60=2024-03-27T08:09:10.123Z");
  }

  // 驗證 Pending New 的 ExecID 會刻意使用 WAL recordId，確保事件可追溯。
  // 情境：送出一筆新單後抓取回送訊息，確認 tag 17 與 WAL recordId 對齊。
  @DisplayName("Pending New 的 ExecID 會使用 WAL recordId 以利追蹤")
  @Test
  void pendingNewExecIdIntentionallyUsesWalRecordIdForTraceability() throws Exception {
    final WalAppender walAppender = new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(publisher.publish(any(OrderCommand.class))).thenReturn(CompletableFuture.completedFuture(null));
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
      .thenReturn(new RiskSubmissionResult("O-C1", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler = new InboundFixMessageHandler(
        walAppender,
        publisher,
      riskSubmissionClient,
        sender,
        new OrderSessionRegistry(),
        new FixMessageMapper(FIXED_CLOCK),
        FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"));

    final WalRecord walRecord = walAppender.readAll().getFirst();
    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());

    assertThat(messageCaptor.getValue().getString(17)).isEqualTo("E-" + walRecord.recordId());
    assertThat(messageCaptor.getValue().getString(17)).doesNotMatch("E\\d+");
  }

  // 驗證風控提交失敗時，拒絕訊息文字會帶出具體的 reason code 與說明。
  // 情境：模擬 circuit open 失敗，確認 FIX reject text 含有 RISK_CIRCUIT_OPEN 與對應描述。
  @DisplayName("風控提交失敗時拒絕訊息會帶出具體原因碼")
  @Test
  void submitFailureUsesSpecificRiskReasonCodeInRejectText() throws Exception {
    final WalAppender walAppender = new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
        .thenThrow(RiskSubmissionFailure.circuitOpen());
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler = new InboundFixMessageHandler(
        walAppender,
        publisher,
        riskSubmissionClient,
        sender,
        new OrderSessionRegistry(),
        new FixMessageMapper(FIXED_CLOCK),
        FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"));

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("RISK_CIRCUIT_OPEN: risk-service circuit breaker is open");
  }

  private NewOrderSingle newNewOrder(
      String clientOrderId,
      String symbol,
      char side,
      String quantity,
      String price,
      String account) {
    final NewOrderSingle order = new NewOrderSingle();
    order.setString(ClOrdID.FIELD, clientOrderId);
    order.setString(Symbol.FIELD, symbol);
    order.setChar(quickfix.field.Side.FIELD, side);
    order.setString(OrderQty.FIELD, quantity);
    order.setChar(OrdType.FIELD, '2');
    order.setString(Price.FIELD, price);
    order.setChar(HandlInst.FIELD, '1');
    order.setString(TransactTime.FIELD, "20240327-08:09:10.123");
    order.setString(Account.FIELD, account);
    return order;
  }

  private OrderCancelRequest newCancelRequest(String originalClientOrderId, String cancelClientOrderId, String account) {
    final OrderCancelRequest cancel = new OrderCancelRequest();
    cancel.setString(OrigClOrdID.FIELD, originalClientOrderId);
    cancel.setString(ClOrdID.FIELD, cancelClientOrderId);
    cancel.setString(Account.FIELD, account);
    cancel.setString(TransactTime.FIELD, "20240327-08:09:10.123");
    return cancel;
  }
}