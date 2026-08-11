package com.simplematch.quickfixgateway.fix;

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

  InboundFixMessageHandler(
      NewOrderFixMessageHandler newOrderHandler, CancelOrderFixMessageHandler cancelOrderHandler) {
    this.newOrderHandler = newOrderHandler;
    this.cancelOrderHandler = cancelOrderHandler;
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
