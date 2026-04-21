package com.simplematch.quickfixgateway.fix;

import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;

public final class QuickFixSessionMessageSender implements FixSessionMessageSender {
  @Override
  public void send(SessionID sessionId, Message message) {
    try {
      if (!Session.sendToTarget(message, sessionId)) {
        throw new IllegalStateException("sendToTarget failed for session " + sessionId);
      }
    } catch (SessionNotFound exception) {
      throw new IllegalStateException("session not found for " + sessionId, exception);
    }
  }
}