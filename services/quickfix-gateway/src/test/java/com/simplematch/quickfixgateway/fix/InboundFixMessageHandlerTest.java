package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.MsgType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;

class InboundFixMessageHandlerTest {
  @TempDir Path tempDir;

  private static final Instant FIXED_INSTANT = Instant.parse("2024-03-27T08:09:10.123Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

  // Verify that the new-order baseline flow writes the WAL, publishes the OrderCommand, registers
  // session state, and sends a Pending New response.
  // Scenario: submit a valid limit order and check that the WAL, command contents, registry state,
  // and FIX response all match.
  @DisplayName(
      "the new-order baseline flow writes WAL, publishes the command, and sends Pending New")
  @Test
  void newOrderBaselinePathWritesExactWalPublishesExactCommandRegistersStateAndSendsPendingNew()
      throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(publisher.publish(any(OrderCommand.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("O-C1", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final FixMessageMapper mapper = new FixMessageMapper(FIXED_CLOCK);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender, publisher, riskSubmissionClient, sender, registry, mapper, FIXED_CLOCK);

    final NewOrderSingle order = newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1");
    final SessionID sessionId = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");
    handler.handle(order, sessionId);

    final List<WalRecord> walRecords = walAppender.readAll();
    assertThat(walRecords).hasSize(1);

    final WalRecord walRecord = walRecords.getFirst();
    assertThat(walRecord.schemaVersion()).isEqualTo("v1");
    assertThat(walRecord.recordId()).isNotBlank();
    assertUuidVersionSeven(walRecord.recordId());
    assertThat(walRecord.createdAtUnixMs()).isEqualTo(FIXED_INSTANT.toEpochMilli());
    assertThat(walRecord.sourceService()).isEqualTo("quickfix-gateway");
    assertThat(walRecord.senderCompId()).isEqualTo("CLIENT1");
    assertThat(walRecord.targetCompId()).isEqualTo("SIMPLEMATCH");
    assertThat(walRecord.messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
    assertThat(walRecord.orderId()).isEqualTo("O-C1");
    assertThat(walRecord.clOrdId()).isEqualTo("C1");
    assertThat(walRecord.origClOrdId()).isEmpty();
    assertThat(walRecord.accountId()).isEqualTo("ACC-1");
    assertThat(walRecord.symbol()).isEqualTo("AAPL");
    assertThat(walRecord.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(walRecord.quantity()).isEqualTo("10");
    assertThat(walRecord.price()).isEqualTo("101.25");
    assertThat(walRecord.orderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(walRecord.tif()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(walRecord.commandType()).isEqualTo(CommandType.COMMAND_TYPE_NEW);
    assertThat(walRecord.rawFix())
        .contains("35=D")
        .contains("11=C1")
        .contains("55=AAPL")
        .contains("38=10");

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
    assertThat(command.getSenderCompId()).isEqualTo("CLIENT1");
    assertThat(command.getTargetCompId()).isEqualTo("SIMPLEMATCH");
    assertThat(command.getClOrdId()).isEqualTo("C1");
    assertThat(command.getOrigClOrdId()).isEmpty();
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
    assertThat(sessionState.clOrdId()).isEqualTo("C1");
    assertThat(sessionState.symbol()).isEqualTo("AAPL");
    assertThat(sessionState.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(sessionState.quantity()).isEqualTo("10");
    assertThat(sessionState.lifecycle().currentOrdStatus()).isEqualTo('A');
    assertThat(sessionState.lifecycle().lastCancelRequest()).isNull();

    final ArgumentCaptor<SessionID> sessionCaptor = ArgumentCaptor.forClass(SessionID.class);
    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(sessionCaptor.capture(), messageCaptor.capture());
    assertThat(sessionCaptor.getValue()).isEqualTo(sessionId);
    assertThat(FixMessageSnapshot.snapshot(messageCaptor.getValue(), 35, 37, 150, 39, 11, 55))
        .startsWith("35=8|37=O-C1|150=A|39=A|11=C1|55=AAPL");
    assertThat(
            FixMessageSnapshot.snapshot(
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

  // Verify that cancel support does not pollute the baseline behavior for subsequent new orders.
  // Scenario: send a new order, then a cancel, then a second new order, and confirm the WAL order,
  // registry state, and second Pending New response are all correct.
  @DisplayName("the cancel flow does not affect the subsequent new-order baseline flow")
  @Test
  void cancelSupportDoesNotAlterSubsequentNewOrderBaselinePath() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(publisher.publish(any(OrderCommand.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    when(riskSubmissionClient.submitCancel(any(OrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender,
            publisher,
            riskSubmissionClient,
            sender,
            registry,
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    final SessionID sessionId = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");
    handler.handle(newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"), sessionId);
    handler.handle(newCancelRequest("C1", "CXL-1", "ACC-1"), sessionId);
    handler.handle(newNewOrder("C2", "MSFT", '2', "20", "305.50", "ACC-2"), sessionId);

    final List<WalRecord> walRecords = walAppender.readAll();
    assertThat(walRecords).hasSize(3);
    assertUuidVersionSeven(walRecords.get(0).recordId());
    assertUuidVersionSeven(walRecords.get(1).recordId());
    assertUuidVersionSeven(walRecords.get(2).recordId());
    assertThat(walRecords.get(0).messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
    assertThat(walRecords.get(1).messageType()).isEqualTo(OrderCancelRequest.MSGTYPE);
    assertThat(walRecords.get(2).messageType()).isEqualTo(NewOrderSingle.MSGTYPE);
    assertThat(walRecords.get(2).orderId()).isEqualTo("O-C2");
    assertThat(walRecords.get(2).side()).isEqualTo(Side.SIDE_SELL);

    final OrderSessionState firstOrderState = registry.find("O-C1").orElseThrow();
    assertThat(firstOrderState.lifecycle().lastCancelRequest()).isNotNull();
    assertThat(firstOrderState.lifecycle().lastCancelRequest().cancelClOrdId()).isEqualTo("CXL-1");
    assertThat(firstOrderState.lifecycle().lastCancelRequest().origClOrdId()).isEqualTo("C1");

    final OrderSessionState secondOrderState = registry.find("O-C2").orElseThrow();
    assertThat(secondOrderState.accountId()).isEqualTo("ACC-2");
    assertThat(secondOrderState.clOrdId()).isEqualTo("C2");
    assertThat(secondOrderState.symbol()).isEqualTo("MSFT");
    assertThat(secondOrderState.side()).isEqualTo(Side.SIDE_SELL);
    assertThat(secondOrderState.quantity()).isEqualTo("20");
    assertThat(secondOrderState.lifecycle().currentOrdStatus()).isEqualTo('A');
    assertThat(secondOrderState.lifecycle().lastCancelRequest()).isNull();

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender, times(2)).send(any(SessionID.class), messageCaptor.capture());
    final Message secondPendingNew = messageCaptor.getAllValues().get(1);
    assertThat(
            FixMessageSnapshot.snapshot(
                secondPendingNew, MsgType.FIELD, 37, 150, 39, 54, 151, 14, 6, 11, 55, 60))
        .isEqualTo(
            "35=8|37=O-C2|150=A|39=A|54=2|151=20|14=0|6=0|11=C2|55=MSFT|60=2024-03-27T08:09:10.123Z");
  }

  // Verify that the Pending New ExecID intentionally uses the WAL recordId so the event remains
  // traceable.
  // Scenario: send a new order and inspect the returned message to confirm tag 17 matches the WAL
  // recordId.
  @DisplayName("Pending New ExecID uses the WAL recordId for traceability")
  @Test
  void pendingNewExecIdIntentionallyUsesWalRecordIdForTraceability() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(publisher.publish(any(OrderCommand.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("O-C1", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender,
            publisher,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    final WalRecord walRecord = walAppender.readAll().getFirst();
    assertUuidVersionSeven(walRecord.recordId());
    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());

    assertThat(messageCaptor.getValue().getString(17)).isEqualTo("E-" + walRecord.recordId());
    assertThat(messageCaptor.getValue().getString(17)).doesNotMatch("E\\d+");
  }

  // Verify that when risk submission fails, the reject message text includes the specific reason
  // code and explanation.
  // Scenario: simulate a circuit-open failure and confirm the FIX reject text contains
  // RISK_CIRCUIT_OPEN and the matching description.
  @DisplayName("risk submission failures surface a specific reason code in the reject message")
  @Test
  void submitFailureUsesSpecificRiskReasonCodeInRejectText() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
        .thenThrow(RiskSubmissionFailure.circuitOpen());
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender,
            publisher,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("RISK_CIRCUIT_OPEN: risk-service circuit breaker is open");
  }

  @DisplayName("oversized new-order client identity is rejected before WAL append")
  @Test
  void oversizedNewOrderIdentityIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender,
            publisher,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder(oversizedIdentity("C"), "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient, publisher);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("OVERSIZED_CL_ORD_ID: cl_ord_id must be <= 64 characters");
  }

  @DisplayName("oversized cancel original client order id is rejected before WAL append")
  @Test
  void oversizedCancelIdentityIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender,
            publisher,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newCancelRequest(oversizedIdentity("ORIG-"), "CXL-1", "ACC-1"),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient, publisher);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("OVERSIZED_ORIG_CL_ORD_ID: orig_cl_ord_id must be <= 64 characters");
  }

  @DisplayName("oversized inbound sender comp id is rejected before WAL append")
  @Test
  void oversizedSessionIdentityIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        new InboundFixMessageHandler(
            walAppender,
            publisher,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "SIMPLEMATCH", oversizedIdentity("CLIENT")));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient, publisher);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("OVERSIZED_SENDER_COMP_ID: sender_comp_id must be <= 64 characters");
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

  private OrderCancelRequest newCancelRequest(
      String originalClientOrderId, String cancelClientOrderId, String account) {
    final OrderCancelRequest cancel = new OrderCancelRequest();
    cancel.setString(OrigClOrdID.FIELD, originalClientOrderId);
    cancel.setString(ClOrdID.FIELD, cancelClientOrderId);
    cancel.setString(Account.FIELD, account);
    cancel.setString(TransactTime.FIELD, "20240327-08:09:10.123");
    return cancel;
  }

  private void assertUuidVersionSeven(String rawUuid) {
    assertThat(UUID.fromString(rawUuid).version()).isEqualTo(7);
  }

  private String oversizedIdentity(String prefix) {
    return prefix + "X".repeat(65);
  }
}
