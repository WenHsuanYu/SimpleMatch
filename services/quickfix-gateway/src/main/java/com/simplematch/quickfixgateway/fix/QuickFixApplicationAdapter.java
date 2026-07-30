package com.simplematch.quickfixgateway.fix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

public final class QuickFixApplicationAdapter implements Application {
  private static final Logger logger = LoggerFactory.getLogger(QuickFixApplicationAdapter.class);

  private final InboundFixMessageHandler inboundFixMessageHandler;

  public QuickFixApplicationAdapter(InboundFixMessageHandler inboundFixMessageHandler) {
    this.inboundFixMessageHandler = inboundFixMessageHandler;
  }

  @Override
  public void onCreate(SessionID sessionId) {
    logger.info("quickfix-gateway session created: {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    logger.info("quickfix-gateway logon: {}", sessionId);
  }

  @Override
  public void onLogout(SessionID sessionId) {
    logger.info("quickfix-gateway logout: {}", sessionId);
  }

  @Override
  public void toAdmin(Message message, SessionID sessionId) {
    logger.debug("toAdmin session={} msg={}", sessionId, message);
  }

  @Override
  public void toApp(Message message, SessionID sessionId) throws DoNotSend {
    logger.debug("toApp session={} msg={}", sessionId, message);
  }

  @Override
  public void fromAdmin(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    logger.debug("fromAdmin session={} msg={}", sessionId, message);
  }

  @Override
  public void fromApp(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
    logger.info("fromApp session={} msg={}", sessionId, message);
    inboundFixMessageHandler.handle(message, sessionId);
  }
}
