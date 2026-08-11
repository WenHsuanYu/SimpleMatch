package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionFailure;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
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
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";
  private static final String SECOND_ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c14";

  @DisplayName("the new-order baseline writes WAL, submits v2 Risk, and sends Pending New")
  @Test
  void newOrderBaselineWritesWalSubmitsV2RegistersStateAndSendsPendingNew() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
        .thenAnswer(
            invocation -> {
              assertThat(walAppender.readAll()).hasSize(1);
              assertThat(registry.find("O-C1")).isEmpty();
              return new RiskSubmissionResult("internal-order", true, "", "");
            });
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final FixMessageMapper mapper = new FixMessageMapper(FIXED_CLOCK);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender, riskSubmissionClient, sender, registry, mapper, FIXED_CLOCK);

    final NewOrderSingle order = newNewOrder("C1", "AAPL", '1', "10", "101.25", ACCOUNT_ID);
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
    assertThat(walRecord.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(walRecord.symbol()).isEqualTo("AAPL");
    assertThat(walRecord.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(walRecord.quantity()).isEqualTo("10");
    assertThat(walRecord.price()).isEqualTo("101.25");
    assertThat(walRecord.orderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(walRecord.tif()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(walRecord.commandType()).isEqualTo(WalCommand.Type.COMMAND_TYPE_NEW);
    assertThat(walRecord.rawFix())
        .contains("35=D")
        .contains("11=C1")
        .contains("55=AAPL")
        .contains("38=10");

    final ArgumentCaptor<NewOrderCommand> commandCaptor =
        ArgumentCaptor.forClass(NewOrderCommand.class);
    verify(riskSubmissionClient).submitNewOrder(commandCaptor.capture());
    final NewOrderCommand command = commandCaptor.getValue();
    assertThat(command.getMetadata().getSchemaVersion()).isEqualTo("v2");
    assertThat(command.getMetadata().getEventId()).isEqualTo(walRecord.recordId());
    assertThat(command.getMetadata().getCreatedAtUnixMs()).isEqualTo(FIXED_INSTANT.toEpochMilli());
    assertThat(command.getMetadata().getSourceService()).isEqualTo("quickfix-gateway");
    assertThat(command.getCommandId()).isEqualTo(walRecord.recordId());
    assertThat(UUID.fromString(command.getOrderId())).isNotNull();
    assertThat(command.getOrderId()).isNotEqualTo(walRecord.orderId());
    assertThat(command.getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(command.getSenderCompId()).isEqualTo("CLIENT1");
    assertThat(command.getTargetCompId()).isEqualTo("SIMPLEMATCH");
    assertThat(command.getClOrdId()).isEqualTo("C1");
    assertThat(command.getInstrument().getSymbol()).isEqualTo("AAPL");
    assertThat(command.getSide()).isEqualTo(Side.SIDE_BUY);
    assertThat(command.getQuantity().getShares()).isEqualTo(10L);
    assertThat(command.getLimitPrice().getUnits()).isEqualTo(1_012_500L);
    assertThat(command.getOrderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(command.getTif()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);

    final OrderSessionState sessionState = registry.find("O-C1").orElseThrow();
    assertThat(sessionState.sessionId()).isEqualTo(sessionId);
    assertThat(sessionState.orderId()).isEqualTo("O-C1");
    assertThat(sessionState.accountId()).isEqualTo(ACCOUNT_ID);
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

  @DisplayName("the cancel flow does not affect the subsequent new-order baseline flow")
  @Test
  void cancelSupportDoesNotAlterSubsequentNewOrderBaselinePath() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    when(riskSubmissionClient.submitCancel(any(CancelOrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            registry,
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    final SessionID sessionId = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");
    handler.handle(newNewOrder("C1", "AAPL", '1', "10", "101.25", ACCOUNT_ID), sessionId);
    handler.handle(newCancelRequest("C1", "CXL-1", ACCOUNT_ID), sessionId);
    handler.handle(
        newNewOrder("C2", "MSFT", '2', "20", "305.50", SECOND_ACCOUNT_ID), sessionId);

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
    assertThat(secondOrderState.accountId()).isEqualTo(SECOND_ACCOUNT_ID);
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

  @DisplayName("Pending New ExecID uses the WAL recordId for traceability")
  @Test
  void pendingNewExecIdIntentionallyUsesWalRecordIdForTraceability() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("internal-order", true, "", ""));
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    final WalRecord walRecord = walAppender.readAll().getFirst();
    assertUuidVersionSeven(walRecord.recordId());
    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());

    assertThat(messageCaptor.getValue().getString(17)).isEqualTo("E-" + walRecord.recordId());
    assertThat(messageCaptor.getValue().getString(17)).doesNotMatch("E\\d+");
  }

  @DisplayName("all supported FIX order forms preserve typed intent at the Risk seam")
  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedOrderForms")
  void supportedOrderFormsReachRiskWithUnchangedMeaning(
      String scenario,
      char orderType,
      char timeInForce,
      String price,
      OrderType expectedOrderType,
      TimeInForce expectedTimeInForce)
      throws Exception {
    try (WalAppender walAppender =
        new WalAppender(tempDir.resolve(scenario + ".wal"), StandardCharsets.UTF_8)) {
      final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
      when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
          .thenReturn(new RiskSubmissionResult("internal-order", true, "", ""));
      final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
      final InboundFixMessageHandler handler =
          QuickFixIngressTestFixture.compose(
              walAppender,
              riskSubmissionClient,
              sender,
              new OrderSessionRegistry(),
              new FixMessageMapper(FIXED_CLOCK),
              FIXED_CLOCK);

      handler.handle(
          newNewOrder(
              scenario,
              "2330",
              '1',
              "100",
              orderType,
              timeInForce,
              price,
              ACCOUNT_ID),
          new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

      final ArgumentCaptor<NewOrderCommand> commandCaptor =
          ArgumentCaptor.forClass(NewOrderCommand.class);
      verify(riskSubmissionClient).submitNewOrder(commandCaptor.capture());
      final NewOrderCommand command = commandCaptor.getValue();
      assertThat(command.getInstrument().getSymbol()).isEqualTo("2330");
      assertThat(command.getQuantity().getShares()).isEqualTo(100L);
      assertThat(command.getOrderType()).isEqualTo(expectedOrderType);
      assertThat(command.getTif()).isEqualTo(expectedTimeInForce);
      if (expectedOrderType == OrderType.ORDER_TYPE_LIMIT) {
        assertThat(command.hasLimitPrice()).isTrue();
      } else {
        assertThat(command.hasLimitPrice()).isFalse();
      }
      assertThat(walAppender.readAll()).singleElement().satisfies(record -> {
        assertThat(record.orderType()).isEqualTo(expectedOrderType);
        assertThat(record.tif()).isEqualTo(expectedTimeInForce);
        assertThat(record.price()).isEqualTo(price);
      });
    }
  }

  private static Stream<Arguments> supportedOrderForms() {
    return Stream.of(
        Arguments.of(
            "limit-rod", '2', '0', "101.25", OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_ROD),
        Arguments.of(
            "limit-ioc", '2', '3', "101.25", OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_IOC),
        Arguments.of(
            "limit-fok", '2', '4', "101.25", OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_FOK),
        Arguments.of(
            "market-rod", '1', '0', "", OrderType.ORDER_TYPE_MARKET,
            TimeInForce.TIME_IN_FORCE_ROD),
        Arguments.of(
            "market-ioc", '1', '3', "", OrderType.ORDER_TYPE_MARKET,
            TimeInForce.TIME_IN_FORCE_IOC),
        Arguments.of(
            "market-fok", '1', '4', "", OrderType.ORDER_TYPE_MARKET,
            TimeInForce.TIME_IN_FORCE_FOK));
  }

  @DisplayName("unsupported order type or time in force is rejected before WAL and Risk")
  @ParameterizedTest(name = "{0}")
  @MethodSource("unsupportedOrderForms")
  void unsupportedOrderFormsDoNotEnterDurableAdmission(
      String scenario, char orderType, char timeInForce) throws Exception {
    try (WalAppender walAppender =
        new WalAppender(tempDir.resolve(scenario + ".wal"), StandardCharsets.UTF_8)) {
      final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
      final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
      final InboundFixMessageHandler handler =
          QuickFixIngressTestFixture.compose(
              walAppender,
              riskSubmissionClient,
              sender,
              new OrderSessionRegistry(),
              new FixMessageMapper(FIXED_CLOCK),
              FIXED_CLOCK);

      final NewOrderSingle order =
          newNewOrder(
              scenario, "2330", '1', "100", orderType, timeInForce, "101.25", ACCOUNT_ID);

      handler.handle(order, new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));
      verify(sender).send(any(SessionID.class), any(Message.class));
      verifyNoInteractions(riskSubmissionClient);
      assertThat(walAppender.readAll()).isEmpty();
    }
  }

  private static Stream<Arguments> unsupportedOrderForms() {
    return Stream.of(
        Arguments.of("unsupported-order-type", '3', '0'),
        Arguments.of("unsupported-time-in-force", '2', '1'));
  }

  @Test
  void equivalentDuplicateNewOrderHasOneDurableRiskOutcomeAndOnePendingResponse() throws Exception {
    try (WalAppender walAppender =
        new WalAppender(tempDir.resolve("duplicate-new.wal"), StandardCharsets.UTF_8)) {
      final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
      when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
          .thenReturn(new RiskSubmissionResult("internal-order", true, "", ""));
      final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
      final InboundFixMessageHandler handler =
          QuickFixIngressTestFixture.compose(
              walAppender,
              riskSubmissionClient,
              sender,
              new OrderSessionRegistry(),
              new FixMessageMapper(FIXED_CLOCK),
              FIXED_CLOCK);
      final SessionID sessionId = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");
      final NewOrderSingle order =
          newNewOrder("C1", "2330", '1', "100", '2', '0', "101.25", ACCOUNT_ID);

      handler.handle(order, sessionId);
      handler.handle(order, sessionId);

      verify(riskSubmissionClient, times(1)).submitNewOrder(any(NewOrderCommand.class));
      verify(sender, times(1)).send(any(SessionID.class), any(Message.class));
      assertThat(walAppender.readAll()).hasSize(1);
    }
  }

  @Test
  void interruptedCancellationIsRejectedBeforeWalAndRisk() throws Exception {
    final GatewayAdmissionGate admissionGate = new GatewayAdmissionGate();
    admissionGate.interruptMarket();
    try (WalAppender walAppender =
        new WalAppender(tempDir.resolve("interrupted-cancel.wal"), StandardCharsets.UTF_8)) {
      final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
      final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
      final InboundFixMessageHandler handler =
          QuickFixIngressTestFixture.compose(
              walAppender,
              riskSubmissionClient,
              sender,
              new OrderSessionRegistry(),
              new FixMessageMapper(FIXED_CLOCK),
              FIXED_CLOCK,
              admissionGate);

      handler.handle(
          newCancelRequest("C1", "CXL-1", ACCOUNT_ID),
          new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

      verify(sender).send(any(SessionID.class), any(Message.class));
      verifyNoInteractions(riskSubmissionClient);
      assertThat(walAppender.readAll()).isEmpty();
    }
  }

  @Test
  void newOrderPauseStillAdmitsCancellationToWalAndRisk() throws Exception {
    final GatewayAdmissionGate admissionGate = new GatewayAdmissionGate();
    admissionGate.open();
    try (WalAppender walAppender =
        new WalAppender(tempDir.resolve("new-orders-paused-cancel.wal"), StandardCharsets.UTF_8)) {
      final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
      when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
          .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
      when(riskSubmissionClient.submitCancel(any(CancelOrderCommand.class)))
          .thenReturn(new RiskSubmissionResult("accepted", true, "", ""));
      final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
      final InboundFixMessageHandler handler =
          QuickFixIngressTestFixture.compose(
              walAppender,
              riskSubmissionClient,
              sender,
              new OrderSessionRegistry(),
              new FixMessageMapper(FIXED_CLOCK),
              FIXED_CLOCK,
              admissionGate);
      final SessionID sessionId = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");

      handler.handle(newNewOrder("C1", "2330", '1', "100", "101.25", ACCOUNT_ID), sessionId);
      admissionGate.pauseNewOrders("MATCHING_PARTITION_RECOVERING");
      handler.handle(newCancelRequest("C1", "CXL-1", ACCOUNT_ID), sessionId);

      verify(riskSubmissionClient).submitCancel(any(CancelOrderCommand.class));
      assertThat(walAppender.readAll()).hasSize(2);
    }
  }

  @DisplayName("risk transport failures expose only a client-safe system error")
  @Test
  void submitFailureUsesClientSafeSystemErrorText() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(riskSubmissionClient.submitNewOrder(any(NewOrderCommand.class)))
        .thenThrow(RiskSubmissionFailure.circuitOpen());
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    final Message response = messageCaptor.getValue();
    assertThat(response.getChar(150)).isEqualTo('A');
    assertThat(response.getChar(39)).isEqualTo('A');
    assertThat(response.getString(58))
        .isEqualTo(
            "SYSTEM_ERROR: order outcome is pending confirmation; no client action is required");
    assertThat(response.getString(58))
        .doesNotContain("RISK_CIRCUIT_OPEN", "circuit breaker", "risk-service");
  }

  @DisplayName("an invalid new order is rejected before WAL append")
  @Test
  void invalidNewOrderIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "-1", "101.25", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("INVALID_NEW_ORDER: quantity must be positive");
  }

  @DisplayName("an invalid account id is rejected before WAL append")
  @Test
  void invalidAccountIdIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("invalid-account.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", "ACC-1"),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);
    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("INVALID_NEW_ORDER: account_id must be a UUID");
  }

  @DisplayName("a missing new-order field is rejected before WAL append")
  @Test
  void missingNewOrderFieldIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);
    final NewOrderSingle order = newNewOrder("C1", "AAPL", '1', "10", "101.25", ACCOUNT_ID);
    order.removeField(Symbol.FIELD);

    handler.handle(order, new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("INVALID_NEW_ORDER: required FIX field is missing");
  }

  @DisplayName("an invalid new-order side is rejected before WAL append")
  @Test
  void invalidNewOrderSideIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", 'X', "10", "101.25", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("INVALID_NEW_ORDER: side must be specified");
  }

  @DisplayName("a cancel without an original client order id is rejected before WAL append")
  @Test
  void cancelWithoutOriginalClientOrderIdIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    final OrderCancelRequest cancel = newCancelRequest("C1", "CXL-1", ACCOUNT_ID);
    cancel.removeField(OrigClOrdID.FIELD);
    handler.handle(cancel, new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("MISSING_ORIG_CL_ORD_ID: orig_cl_ord_id must not be blank");
  }

  @DisplayName("oversized new-order client identity is rejected before WAL append")
  @Test
  void oversizedNewOrderIdentityIsRejectedBeforeWalAppend() throws Exception {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder(oversizedIdentity("C"), "AAPL", '1', "10", "101.25", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

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
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newCancelRequest(oversizedIdentity("ORIG-"), "CXL-1", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1"));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

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
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);

    handler.handle(
        newNewOrder("C1", "AAPL", '1', "10", "101.25", ACCOUNT_ID),
        new SessionID("FIX.4.4", "SIMPLEMATCH", oversizedIdentity("CLIENT")));

    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient);

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().getString(58))
        .isEqualTo("OVERSIZED_SENDER_COMP_ID: sender_comp_id must be <= 64 characters");
  }

  @DisplayName("unsupported application messages are rejected by the inbound dispatcher")
  @Test
  void unsupportedApplicationMessageDoesNotEnterEitherDurablePath() {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("inbound.wal"), StandardCharsets.UTF_8);
    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final InboundFixMessageHandler handler =
        QuickFixIngressTestFixture.compose(
            walAppender,
            riskSubmissionClient,
            sender,
            new OrderSessionRegistry(),
            new FixMessageMapper(FIXED_CLOCK),
            FIXED_CLOCK);
    final Message unsupported = new Message();
    unsupported.getHeader().setString(MsgType.FIELD, "X");

    assertThatThrownBy(
            () -> handler.handle(
                unsupported, new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1")))
        .isInstanceOf(UnsupportedMessageType.class);
    assertThat(walAppender.readAll()).isEmpty();
    verifyNoInteractions(riskSubmissionClient, sender);
  }

  private NewOrderSingle newNewOrder(
      String clientOrderId,
      String symbol,
      char side,
      String quantity,
      String price,
      String account) {
    return newNewOrder(clientOrderId, symbol, side, quantity, '2', '0', price, account);
  }

  private NewOrderSingle newNewOrder(
      String clientOrderId,
      String symbol,
      char side,
      String quantity,
      char orderType,
      char timeInForce,
      String price,
      String account) {
    final NewOrderSingle order = new NewOrderSingle();
    order.setString(ClOrdID.FIELD, clientOrderId);
    order.setString(Symbol.FIELD, symbol);
    order.setChar(quickfix.field.Side.FIELD, side);
    order.setString(OrderQty.FIELD, quantity);
    order.setChar(OrdType.FIELD, orderType);
    if (!price.isEmpty()) {
      order.setString(Price.FIELD, price);
    }
    order.setChar(quickfix.field.TimeInForce.FIELD, timeInForce);
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
