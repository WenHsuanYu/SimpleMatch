package com.simplematch.quickfixgateway.fix;

import quickfix.Message;
import quickfix.SessionID;

public interface FixSessionMessageSender {
  void send(SessionID sessionId, Message message);
}
