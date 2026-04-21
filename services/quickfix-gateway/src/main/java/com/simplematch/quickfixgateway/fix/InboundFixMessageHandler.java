package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionFailure;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldMap;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.MsgType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.fix44.OrderCancelRequest;

public final class InboundFixMessageHandler {
  private static final Logger logger = LoggerFactory.getLogger(InboundFixMessageHandler.class);

  private final WalAppender walAppender;
  private final OrdersCommandPublisher ordersCommandPublisher;
  private final RiskSubmissionClient riskSubmissionClient;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final OrderSessionRegistry orderSessionRegistry;
  private final FixMessageMapper fixMessageMapper;
  private final Clock clock;

  public InboundFixMessageHandler(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      Clock clock) {
    this.walAppender = walAppender;
    this.ordersCommandPublisher = ordersCommandPublisher;
    this.riskSubmissionClient = riskSubmissionClient;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.orderSessionRegistry = orderSessionRegistry;
    this.fixMessageMapper = fixMessageMapper;
    this.clock = clock;
  }

  public void handle(Message message, SessionID sessionId) throws FieldNotFound, UnsupportedMessageType {
    final String msgType = header(message).getString(MsgType.FIELD);
    if (quickfix.fix44.NewOrderSingle.MSGTYPE.equals(msgType)) {
      handleNewOrder(message, sessionId);
      return;
    }
    if (OrderCancelRequest.MSGTYPE.equals(msgType)) {
      handleCancelOrder(message, sessionId);
      return;
    }
    throw new UnsupportedMessageType();
  }

  private void handleNewOrder(Message message, SessionID sessionId) throws FieldNotFound {
    final Instant now = Instant.now(clock);
    final String clientOrderId = message.getString(ClOrdID.FIELD);
    final String symbol = message.getString(Symbol.FIELD);
    final Side side = mapSide(message.getChar(quickfix.field.Side.FIELD));
    final String quantity = message.getString(OrderQty.FIELD);
    final String orderId = orderIdFor(clientOrderId);
    final WalRecord walRecord = new WalRecord(
        "v1",
        UUID.randomUUID().toString(),
        now.toEpochMilli(),
        "quickfix-gateway",
        sessionId.toString(),
        quickfix.fix44.NewOrderSingle.MSGTYPE,
        orderId,
        clientOrderId,
        "",
        optionalString(message, Account.FIELD),
        symbol,
        side,
        quantity,
        optionalString(message, Price.FIELD),
        mapOrderType(optionalChar(message, OrdType.FIELD)),
        mapTimeInForce(optionalChar(message, quickfix.field.TimeInForce.FIELD)),
        com.simplematch.contracts.orders.v1.CommandType.COMMAND_TYPE_NEW,
        message.toString());

    walAppender.appendAndFlush(walRecord);
    final OrderCommand command = walRecord.toOrderCommand();
    final RiskSubmissionResult submission = submitNewOrder(command, sessionId, walRecord, now);
    if (!submission.accepted()) {
      return;
    }

    orderSessionRegistry.registerAcceptedOrder(sessionId, walRecord, 'A');

    final Message pendingNew = fixMessageMapper.buildPendingNew(
        walRecord.orderId(),
        nextPendingExecId(walRecord.recordId()),
        walRecord.clientOrderId(),
        walRecord.symbol(),
        walRecord.side(),
        walRecord.quantity(),
        now);
    fixSessionMessageSender.send(sessionId, pendingNew);

    publishBestEffort(command);
  }

  private void handleCancelOrder(Message message, SessionID sessionId) throws FieldNotFound {
    final String originalClientOrderId = message.getString(OrigClOrdID.FIELD);
    final String cancelClientOrderId = message.getString(ClOrdID.FIELD);
    final String orderId = orderIdFor(originalClientOrderId);
    final OrderSessionState existing = orderSessionRegistry.find(orderId).orElse(null);
    final WalRecord walRecord = new WalRecord(
        "v1",
        UUID.randomUUID().toString(),
        Instant.now(clock).toEpochMilli(),
        "quickfix-gateway",
        sessionId.toString(),
        OrderCancelRequest.MSGTYPE,
        orderId,
        cancelClientOrderId,
        originalClientOrderId,
        optionalString(message, Account.FIELD, existing == null ? "" : existing.accountId()),
        optionalString(message, Symbol.FIELD, existing == null ? "" : existing.symbol()),
        existing == null ? Side.SIDE_UNSPECIFIED : existing.side(),
        existing == null ? "0" : existing.quantity(),
        optionalString(message, Price.FIELD),
        OrderType.ORDER_TYPE_UNSPECIFIED,
        TimeInForce.TIME_IN_FORCE_UNSPECIFIED,
        com.simplematch.contracts.orders.v1.CommandType.COMMAND_TYPE_CANCEL,
        message.toString());

    walAppender.appendAndFlush(walRecord);
    final OrderCommand command = walRecord.toOrderCommand();
    final RiskSubmissionResult submission = submitCancelOrder(command, sessionId, walRecord, existing);
    if (!submission.accepted()) {
      return;
    }

    orderSessionRegistry.registerCancelRequest(sessionId, walRecord);
    publishBestEffort(command);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void publishBestEffort(OrderCommand command) {
    ordersCommandPublisher.publish(command).whenComplete((ignored, error) -> {
      if (error != null) {
        logger.warn("orders.commands publish failed for command_id={}", command.getCommandId(), error);
      }
    });
  }

  private RiskSubmissionResult submitNewOrder(
      OrderCommand command,
      SessionID sessionId,
      WalRecord walRecord,
      Instant now) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitNewOrder(command);
      if (!submission.accepted()) {
        final Message rejected = fixMessageMapper.buildRejected(
            walRecord.orderId(),
            nextRejectedExecId(walRecord.recordId()),
            walRecord.clientOrderId(),
            walRecord.symbol(),
            walRecord.side(),
            walRecord.quantity(),
            rejectText(submission),
            now);
        fixSessionMessageSender.send(sessionId, rejected);
      }
      return submission;
    } catch (RuntimeException error) {
      final RiskSubmissionFailure failure = riskFailure(error, "risk-service submit failed");
      logger.warn("risk-service submit failed for command_id={} reason_code={}", command.getCommandId(), failure.reasonCode(), error);
      final Message rejected = fixMessageMapper.buildRejected(
          walRecord.orderId(),
          nextRejectedExecId(walRecord.recordId()),
          walRecord.clientOrderId(),
          walRecord.symbol(),
          walRecord.side(),
          walRecord.quantity(),
          failure.reasonCode() + ": " + failure.reasonText(),
          now);
      fixSessionMessageSender.send(sessionId, rejected);
      return new RiskSubmissionResult(walRecord.orderId(), false, failure.reasonCode(), failure.reasonText());
    }
  }

  private RiskSubmissionResult submitCancelOrder(
      OrderCommand command,
      SessionID sessionId,
      WalRecord walRecord,
      OrderSessionState existing) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitCancel(command);
      if (!submission.accepted()) {
        final Message rejected = fixMessageMapper.buildOrderCancelReject(
            walRecord.orderId(),
            walRecord.clientOrderId(),
            walRecord.originalClientOrderId(),
            existing == null ? '8' : existing.currentOrdStatus(),
            rejectText(submission));
        fixSessionMessageSender.send(sessionId, rejected);
      }
      return submission;
    } catch (RuntimeException error) {
      final RiskSubmissionFailure failure = riskFailure(error, "risk-service cancel failed");
      logger.warn("risk-service cancel failed for command_id={} reason_code={}", command.getCommandId(), failure.reasonCode(), error);
      final Message rejected = fixMessageMapper.buildOrderCancelReject(
          walRecord.orderId(),
          walRecord.clientOrderId(),
          walRecord.originalClientOrderId(),
          existing == null ? '8' : existing.currentOrdStatus(),
          failure.reasonCode() + ": " + failure.reasonText());
      fixSessionMessageSender.send(sessionId, rejected);
      return new RiskSubmissionResult(walRecord.orderId(), false, failure.reasonCode(), failure.reasonText());
    }
  }

  private RiskSubmissionFailure riskFailure(RuntimeException error, String fallbackReasonText) {
    if (error instanceof RiskSubmissionFailure failure) {
      return failure;
    }
    return RiskSubmissionFailure.unavailable("submit", 1, new IllegalStateException(fallbackReasonText, error));
  }

  private FieldMap header(Message message) {
    return message.getHeader();
  }

  private String orderIdFor(String clientOrderId) {
    return "O-" + clientOrderId;
  }

  private String nextPendingExecId(String recordId) {
    return "E-" + recordId;
  }

  private String nextRejectedExecId(String recordId) {
    return "RJ-" + recordId;
  }

  private String rejectText(RiskSubmissionResult submission) {
    if (submission.reasonCode() == null || submission.reasonCode().isBlank()) {
      return submission.reasonText();
    }
    if (submission.reasonText() == null || submission.reasonText().isBlank()) {
      return submission.reasonCode();
    }
    return submission.reasonCode() + ": " + submission.reasonText();
  }

  private Side mapSide(char value) {
    if (value == '2') {
      return Side.SIDE_SELL;
    }
    return Side.SIDE_BUY;
  }

  private OrderType mapOrderType(Character value) {
    if (value == null) {
      return OrderType.ORDER_TYPE_UNSPECIFIED;
    }
    return switch (value) {
      case '1' -> OrderType.ORDER_TYPE_MARKET;
      case '2' -> OrderType.ORDER_TYPE_LIMIT;
      default -> OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  private TimeInForce mapTimeInForce(Character value) {
    if (value == null || value == '0') {
      return TimeInForce.TIME_IN_FORCE_ROD;
    }
    return switch (value) {
      case '3' -> TimeInForce.TIME_IN_FORCE_IOC;
      case '4' -> TimeInForce.TIME_IN_FORCE_FOK;
      default -> TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }

  private String optionalString(FieldMap fieldMap, int field) throws FieldNotFound {
    return optionalString(fieldMap, field, "");
  }

  private String optionalString(FieldMap fieldMap, int field, String fallback) throws FieldNotFound {
    if (!fieldMap.isSetField(field)) {
      return fallback;
    }
    return fieldMap.getString(field);
  }

  private Character optionalChar(FieldMap fieldMap, int field) throws FieldNotFound {
    if (!fieldMap.isSetField(field)) {
      return null;
    }
    return fieldMap.getChar(field);
  }
}