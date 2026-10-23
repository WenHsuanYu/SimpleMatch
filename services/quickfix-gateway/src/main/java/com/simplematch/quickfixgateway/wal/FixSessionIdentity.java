package com.simplematch.quickfixgateway.wal;

import java.util.Objects;
import quickfix.SessionID;

/** FIX session identities carried by a durable inbound command. */
public record FixSessionIdentity(
    String senderCompId, String targetCompId, String authenticatedSessionId) {
  /** Requires FIX CompIDs and their complete authenticated session identity. */
  public FixSessionIdentity {
    senderCompId = WalValidation.requiredText(senderCompId, "sender_comp_id");
    targetCompId = WalValidation.requiredText(targetCompId, "target_comp_id");
    Objects.requireNonNull(authenticatedSessionId, "session_id");
    if (authenticatedSessionId.isBlank()) {
      throw new IllegalArgumentException("session_id must not be blank");
    }
    final SessionID parsed = new SessionID(authenticatedSessionId);
    if (!targetCompId.equals(parsed.getSenderCompID())
        || !senderCompId.equals(parsed.getTargetCompID())) {
      throw new IllegalArgumentException("session_id does not match FIX CompIDs");
    }
  }

  /** Rehydrates the exact authenticated session captured in the durable command. */
  public SessionID requireAuthenticatedSessionId() {
    return new SessionID(authenticatedSessionId);
  }
}
