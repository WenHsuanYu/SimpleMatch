package com.simplematch.quickfixgateway.fix;

import quickfix.Message;
import quickfix.SessionID;

/** Sends an outbound FIX message to a specific QuickFIX session. */
public interface FixSessionMessageSender {
  /** Sends the message to the supplied session. */
  void send(SessionID sessionId, Message message);
}
