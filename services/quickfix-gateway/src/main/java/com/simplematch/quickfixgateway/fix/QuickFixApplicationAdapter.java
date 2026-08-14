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

/** Adapts QuickFIX/J application callbacks to the gateway's inbound order handler. */
public final class QuickFixApplicationAdapter implements Application {
  private static final Logger logger = LoggerFactory.getLogger(QuickFixApplicationAdapter.class);

  private final InboundFixMessageHandler inboundFixMessageHandler;
  private final FixSessionOwnership sessionOwnership;
  private final String ownerId;

  /** Creates an adapter that delegates inbound application messages to the supplied handler. */
  public QuickFixApplicationAdapter(InboundFixMessageHandler inboundFixMessageHandler) {
    this(inboundFixMessageHandler, new FixSessionOwnership(), "quickfix-gateway-test");
  }

  /** Creates an adapter with an explicit process-local session ownership boundary. */
  public QuickFixApplicationAdapter(
      InboundFixMessageHandler inboundFixMessageHandler,
      FixSessionOwnership sessionOwnership,
      String ownerId) {
    this.inboundFixMessageHandler = inboundFixMessageHandler;
    this.sessionOwnership = sessionOwnership;
    this.ownerId = ownerId;
  }

  @Override
  public void onCreate(SessionID sessionId) {
    logger.info("quickfix-gateway session created: {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    if (!sessionOwnership.tryClaim(sessionId, ownerId)) {
      logger.error("quickfix-gateway rejected conflicting session owner: {}", sessionId);
      return;
    }
    logger.info("quickfix-gateway logon: {} owner={}", sessionId, ownerId);
  }

  @Override
  public void onLogout(SessionID sessionId) {
    sessionOwnership.release(sessionId, ownerId);
    logger.info("quickfix-gateway logout: {}", sessionId);
  }

  @Override
  public void toAdmin(Message message, SessionID sessionId) {
    logger.debug("toAdmin QuickFIX message for session={}", sessionId);
  }

  @Override
  public void toApp(Message message, SessionID sessionId) throws DoNotSend {
    logger.debug("toApp QuickFIX message for session={}", sessionId);
  }

  @Override
  public void fromAdmin(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    logger.debug("fromAdmin QuickFIX message for session={}", sessionId);
  }

  @Override
  public void fromApp(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
    if (!sessionOwnership.tryClaim(sessionId, ownerId)) {
      logger.warn("ignored application message from conflicting session owner: {}", sessionId);
      return;
    }
    logger.info("fromApp QuickFIX message accepted for session={}", sessionId);
    inboundFixMessageHandler.handle(message, sessionId);
  }
}
