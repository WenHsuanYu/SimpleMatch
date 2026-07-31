package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.wal.WalAppender;
import java.time.Clock;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.MsgType;
import quickfix.fix44.OrderCancelRequest;

/** Translates inbound FIX orders into durable, risk-admitted gateway commands. */
public final class InboundFixMessageHandler {
  private final NewOrderFixMessageHandler newOrderHandler;
  private final CancelOrderFixMessageHandler cancelOrderHandler;

  /** Creates the inbound handler with its durable, risk, and FIX-session collaborators. */
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
    final RiskSubmissionResponder riskSubmissionResponder =
        new RiskSubmissionResponder(
            riskSubmissionClient, fixSessionMessageSender, fixMessageMapper);
    final FixCompatibilityCommandPublisher compatibilityPublisher =
        new FixCompatibilityCommandPublisher(ordersCommandPublisher);
    newOrderHandler =
        new NewOrderFixMessageHandler(
            walAppender,
            orderSessionRegistry,
            fixSessionMessageSender,
            fixMessageMapper,
            riskSubmissionResponder,
            compatibilityPublisher,
            commandIdGenerator,
            clock);
    cancelOrderHandler =
        new CancelOrderFixMessageHandler(
            walAppender,
            orderSessionRegistry,
            riskSubmissionResponder,
            compatibilityPublisher,
            commandIdGenerator,
            clock);
  }

  /** Handles one inbound application message for its QuickFIX session. */
  public void handle(Message message, SessionID sessionId)
      throws FieldNotFound, UnsupportedMessageType {
    final String msgType = message.getHeader().getString(MsgType.FIELD);
    if (quickfix.fix44.NewOrderSingle.MSGTYPE.equals(msgType)) {
      newOrderHandler.handle(message, sessionId);
      return;
    }
    if (OrderCancelRequest.MSGTYPE.equals(msgType)) {
      cancelOrderHandler.handle(message, sessionId);
      return;
    }
    throw new UnsupportedMessageType();
  }
}
