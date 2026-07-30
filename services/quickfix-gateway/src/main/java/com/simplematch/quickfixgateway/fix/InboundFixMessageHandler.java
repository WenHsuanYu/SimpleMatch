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

@SuppressWarnings(
    "PMD.TooManyMethods") // FIX ingress transaction coordination remains behind one handler seam.
public final class InboundFixMessageHandler {
  private static final Logger logger = LoggerFactory.getLogger(InboundFixMessageHandler.class);
  private static final int MAX_FIX_IDENTITY_LENGTH = 64;

  private final WalAppender walAppender;
  private final OrdersCommandPublisher ordersCommandPublisher;
  private final RiskSubmissionClient riskSubmissionClient;
  private final FixSessionMessageSender fixSessionMessageSender;
  private final OrderSessionRegistry orderSessionRegistry;
  private final FixMessageMapper fixMessageMapper;
  private final CommandIdGenerator commandIdGenerator;
  private final Clock clock;

  public InboundFixMessageHandler(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      Clock clock) {
    this(
        walAppender,
        ordersCommandPublisher,
        riskSubmissionClient,
        fixSessionMessageSender,
        orderSessionRegistry,
        fixMessageMapper,
        new CommandIdGenerator(),
        clock);
  }

  InboundFixMessageHandler(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      CommandIdGenerator commandIdGenerator,
      Clock clock) {
    this.walAppender = walAppender;
    this.ordersCommandPublisher = ordersCommandPublisher;
    this.riskSubmissionClient = riskSubmissionClient;
    this.fixSessionMessageSender = fixSessionMessageSender;
    this.orderSessionRegistry = orderSessionRegistry;
    this.fixMessageMapper = fixMessageMapper;
    this.commandIdGenerator = commandIdGenerator;
    this.clock = clock;
  }

  public void handle(Message message, SessionID sessionId)
      throws FieldNotFound, UnsupportedMessageType {
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
    final String clOrdId = message.getString(ClOrdID.FIELD);
    final String symbol = message.getString(Symbol.FIELD);
    final Side side = mapSide(message.getChar(quickfix.field.Side.FIELD));
    final String quantity = message.getString(OrderQty.FIELD);
    final String senderCompId = inboundSenderCompId(sessionId);
    final String targetCompId = inboundTargetCompId(sessionId);
    final FixIdentityValidationFailure identityFailure =
        validateFixIdentity(senderCompId, targetCompId, clOrdId, "");
    if (identityFailure != null) {
      rejectOversizedNewOrderIdentity(
          identityFailure, sessionId, clOrdId, symbol, side, quantity, now);
      return;
    }
    final String orderId = orderIdFor(clOrdId);
    final WalRecord walRecord =
        new WalRecord(
            "v1",
            commandIdGenerator.nextCommandId(),
            now.toEpochMilli(),
            "quickfix-gateway",
            senderCompId,
            targetCompId,
            quickfix.fix44.NewOrderSingle.MSGTYPE,
            orderId,
            clOrdId,
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

    final Message pendingNew =
        fixMessageMapper.buildPendingNew(
            walRecord.orderId(),
            nextPendingExecId(walRecord.recordId()),
            walRecord.clOrdId(),
            walRecord.symbol(),
            walRecord.side(),
            walRecord.quantity(),
            now);
    fixSessionMessageSender.send(sessionId, pendingNew);

    publishBestEffort(command);
  }

  private void handleCancelOrder(Message message, SessionID sessionId) throws FieldNotFound {
    final String origClOrdId = message.getString(OrigClOrdID.FIELD);
    final String cancelClOrdId = message.getString(ClOrdID.FIELD);
    final String senderCompId = inboundSenderCompId(sessionId);
    final String targetCompId = inboundTargetCompId(sessionId);
    final FixIdentityValidationFailure identityFailure =
        validateFixIdentity(senderCompId, targetCompId, cancelClOrdId, origClOrdId);
    if (identityFailure != null) {
      rejectOversizedCancelIdentity(identityFailure, sessionId, cancelClOrdId, origClOrdId);
      return;
    }
    final String orderId = orderIdFor(origClOrdId);
    final OrderSessionState existing = orderSessionRegistry.find(orderId).orElse(null);
    final WalRecord walRecord =
        new WalRecord(
            "v1",
            commandIdGenerator.nextCommandId(),
            Instant.now(clock).toEpochMilli(),
            "quickfix-gateway",
            senderCompId,
            targetCompId,
            OrderCancelRequest.MSGTYPE,
            orderId,
            cancelClOrdId,
            origClOrdId,
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
    final RiskSubmissionResult submission =
        submitCancelOrder(command, sessionId, walRecord, existing);
    if (!submission.accepted()) {
      return;
    }

    orderSessionRegistry.registerCancelRequest(sessionId, walRecord);
    publishBestEffort(command);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void publishBestEffort(OrderCommand command) {
    ordersCommandPublisher
        .publish(command)
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                logger.warn(
                    "orders.commands publish failed for command_id={}",
                    command.getCommandId(),
                    error);
              }
            });
  }

  private RiskSubmissionResult submitNewOrder(
      OrderCommand command, SessionID sessionId, WalRecord walRecord, Instant now) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitNewOrder(command);
      if (!submission.accepted()) {
        final Message rejected =
            fixMessageMapper.buildRejected(
                walRecord.orderId(),
                nextRejectedExecId(walRecord.recordId()),
                walRecord.clOrdId(),
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
      logger.warn(
          "risk-service submit failed for command_id={} reason_code={}",
          command.getCommandId(),
          failure.reasonCode(),
          error);
      final Message rejected =
          fixMessageMapper.buildRejected(
              walRecord.orderId(),
              nextRejectedExecId(walRecord.recordId()),
              walRecord.clOrdId(),
              walRecord.symbol(),
              walRecord.side(),
              walRecord.quantity(),
              failure.reasonCode() + ": " + failure.reasonText(),
              now);
      fixSessionMessageSender.send(sessionId, rejected);
      return new RiskSubmissionResult(
          walRecord.orderId(), false, failure.reasonCode(), failure.reasonText());
    }
  }

  private RiskSubmissionResult submitCancelOrder(
      OrderCommand command, SessionID sessionId, WalRecord walRecord, OrderSessionState existing) {
    try {
      final RiskSubmissionResult submission = riskSubmissionClient.submitCancel(command);
      if (!submission.accepted()) {
        final Message rejected =
            fixMessageMapper.buildOrderCancelReject(
                walRecord.orderId(),
                walRecord.clOrdId(),
                walRecord.origClOrdId(),
                existing == null ? '8' : existing.currentOrdStatus(),
                rejectText(submission));
        fixSessionMessageSender.send(sessionId, rejected);
      }
      return submission;
    } catch (RuntimeException error) {
      final RiskSubmissionFailure failure = riskFailure(error, "risk-service cancel failed");
      logger.warn(
          "risk-service cancel failed for command_id={} reason_code={}",
          command.getCommandId(),
          failure.reasonCode(),
          error);
      final Message rejected =
          fixMessageMapper.buildOrderCancelReject(
              walRecord.orderId(),
              walRecord.clOrdId(),
              walRecord.origClOrdId(),
              existing == null ? '8' : existing.currentOrdStatus(),
              failure.reasonCode() + ": " + failure.reasonText());
      fixSessionMessageSender.send(sessionId, rejected);
      return new RiskSubmissionResult(
          walRecord.orderId(), false, failure.reasonCode(), failure.reasonText());
    }
  }

  private RiskSubmissionFailure riskFailure(RuntimeException error, String fallbackReasonText) {
    if (error instanceof RiskSubmissionFailure failure) {
      return failure;
    }
    return RiskSubmissionFailure.unavailable(
        "submit", 1, new IllegalStateException(fallbackReasonText, error));
  }

  private FieldMap header(Message message) {
    return message.getHeader();
  }

  private FixIdentityValidationFailure validateFixIdentity(
      String senderCompId, String targetCompId, String clOrdId, String origClOrdId) {
    final FixIdentityValidationFailure senderFailure =
        oversizedFixIdentity("sender_comp_id", senderCompId, "OVERSIZED_SENDER_COMP_ID");
    if (senderFailure != null) {
      return senderFailure;
    }
    final FixIdentityValidationFailure targetFailure =
        oversizedFixIdentity("target_comp_id", targetCompId, "OVERSIZED_TARGET_COMP_ID");
    if (targetFailure != null) {
      return targetFailure;
    }
    final FixIdentityValidationFailure clOrdIdFailure =
        oversizedFixIdentity("cl_ord_id", clOrdId, "OVERSIZED_CL_ORD_ID");
    if (clOrdIdFailure != null) {
      return clOrdIdFailure;
    }
    return oversizedFixIdentity("orig_cl_ord_id", origClOrdId, "OVERSIZED_ORIG_CL_ORD_ID");
  }

  private FixIdentityValidationFailure oversizedFixIdentity(
      String fieldName, String value, String reasonCode) {
    if (!exceedsFixIdentityLength(value)) {
      return null;
    }
    return new FixIdentityValidationFailure(
        reasonCode, fieldName + " must be <= " + MAX_FIX_IDENTITY_LENGTH + " characters");
  }

  private boolean exceedsFixIdentityLength(String value) {
    return value != null && value.length() > MAX_FIX_IDENTITY_LENGTH;
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

  // QuickFIX exposes the acceptor-side session as local -> remote, so inbound FIX 49/56 are
  // reversed.
  private String inboundSenderCompId(SessionID sessionId) {
    return sessionId.getTargetCompID();
  }

  private String inboundTargetCompId(SessionID sessionId) {
    return sessionId.getSenderCompID();
  }

  private String rejectText(RiskSubmissionResult submission) {
    return rejectText(submission.reasonCode(), submission.reasonText());
  }

  private void rejectOversizedNewOrderIdentity(
      FixIdentityValidationFailure identityFailure,
      SessionID sessionId,
      String clOrdId,
      String symbol,
      Side side,
      String quantity,
      Instant now) {
    final Message rejected =
        fixMessageMapper.buildRejected(
            orderIdFor(clOrdId),
            nextRejectedExecId(commandIdGenerator.nextCommandId()),
            clOrdId,
            symbol,
            side,
            quantity,
            rejectText(identityFailure.reasonCode(), identityFailure.reasonText()),
            now);
    fixSessionMessageSender.send(sessionId, rejected);
  }

  private void rejectOversizedCancelIdentity(
      FixIdentityValidationFailure identityFailure,
      SessionID sessionId,
      String cancelClOrdId,
      String origClOrdId) {
    final Message rejected =
        fixMessageMapper.buildOrderCancelReject(
            orderIdFor(origClOrdId),
            cancelClOrdId,
            origClOrdId,
            '8',
            rejectText(identityFailure.reasonCode(), identityFailure.reasonText()));
    fixSessionMessageSender.send(sessionId, rejected);
  }

  private String rejectText(String reasonCode, String reasonText) {
    if (reasonCode == null || reasonCode.isBlank()) {
      return reasonText;
    }
    if (reasonText == null || reasonText.isBlank()) {
      return reasonCode;
    }
    return reasonCode + ": " + reasonText;
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

  private String optionalString(FieldMap fieldMap, int field, String fallback)
      throws FieldNotFound {
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

  private record FixIdentityValidationFailure(String reasonCode, String reasonText) {}
}
