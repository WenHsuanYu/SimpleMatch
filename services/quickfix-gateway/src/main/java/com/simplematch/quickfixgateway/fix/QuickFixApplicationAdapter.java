package com.simplematch.quickfixgateway.fix;

import java.util.List;
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
import quickfix.field.MsgType;

/** Adapts QuickFIX/J application callbacks to the gateway's inbound order handler. */
public final class QuickFixApplicationAdapter implements Application {
  private static final Logger logger = LoggerFactory.getLogger(QuickFixApplicationAdapter.class);

  private final InboundFixMessageHandler inboundFixMessageHandler;
  private final FixSessionOwnership sessionOwnership;
  private final String ownerId;
  private final FixSessionAccess sessionAccess;

  /** Creates a deny-by-default adapter without provisioned counterparties. */
  public QuickFixApplicationAdapter(InboundFixMessageHandler inboundFixMessageHandler) {
    this(inboundFixMessageHandler, new FixSessionOwnership(), "quickfix-gateway-test");
  }

  /** Creates a deny-by-default adapter with explicit process-local session ownership. */
  public QuickFixApplicationAdapter(
      InboundFixMessageHandler inboundFixMessageHandler,
      FixSessionOwnership sessionOwnership,
      String ownerId) {
    this(
        inboundFixMessageHandler,
        sessionOwnership,
        ownerId,
        new FixSessionAccess(List.of(), new OrderSessionRegistry()));
  }

  /**
   * Creates an adapter with explicit process ownership and authenticated counterparty policy.
   *
   * @param inboundFixMessageHandler validated order pipeline
   * @param sessionOwnership process-local ownership registry
   * @param ownerId stable process owner identity
   * @param sessionAccess provisioned counterparty authentication and account authorization
   */
  public QuickFixApplicationAdapter(
      InboundFixMessageHandler inboundFixMessageHandler,
      FixSessionOwnership sessionOwnership,
      String ownerId,
      FixSessionAccess sessionAccess) {
    this.inboundFixMessageHandler = inboundFixMessageHandler;
    this.sessionOwnership = sessionOwnership;
    this.ownerId = ownerId;
    this.sessionAccess = sessionAccess;
  }

  @Override
  public void onCreate(SessionID sessionId) {
    logger.info("quickfix-gateway session created: {}", sessionId);
  }

  @Override
  public void onLogon(SessionID sessionId) {
    if (!sessionAccess.logon(sessionId)) {
      logger.warn("ignored FIX Logon without verified credentials for session={}", sessionId);
      return;
    }
    if (!sessionOwnership.tryClaim(sessionId, ownerId)) {
      sessionAccess.logout(sessionId);
      logger.error("quickfix-gateway rejected conflicting session owner: {}", sessionId);
      return;
    }
    logger.info("quickfix-gateway logon: {} owner={}", sessionId, ownerId);
  }

  @Override
  public void onLogout(SessionID sessionId) {
    sessionAccess.logout(sessionId);
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
    if (MsgType.LOGON.equals(message.getHeader().getString(MsgType.FIELD))) {
      sessionAccess.authenticate(message, sessionId);
    }
    logger.debug("fromAdmin QuickFIX message for session={}", sessionId);
  }

  @Override
  public void fromApp(Message message, SessionID sessionId)
      throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
    if (!sessionOwnership.tryClaim(sessionId, ownerId)) {
      logger.warn("ignored application message from conflicting session owner: {}", sessionId);
      return;
    }
    sessionAccess.authorize(message, sessionId);
    logger.info("fromApp QuickFIX message accepted for session={}", sessionId);
    inboundFixMessageHandler.handle(message, sessionId);
  }
}
