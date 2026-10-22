package com.simplematch.quickfixgateway.wal;

import java.util.Objects;
import quickfix.SessionID;

/** FIX session identities carried by a durable inbound command. */
public record FixSessionIdentity(
    String senderCompId, String targetCompId, String authenticatedSessionId) {
  /** Requires FIX CompIDs and validates any complete authenticated session identity. */
  public FixSessionIdentity {
    senderCompId = WalValidation.requiredText(senderCompId, "sender_comp_id");
    targetCompId = WalValidation.requiredText(targetCompId, "target_comp_id");
    Objects.requireNonNull(authenticatedSessionId, "session_id");
    if (!authenticatedSessionId.isBlank()) {
      final SessionID parsed = new SessionID(authenticatedSessionId);
      if (!targetCompId.equals(parsed.getSenderCompID())
          || !senderCompId.equals(parsed.getTargetCompID())) {
        throw new IllegalArgumentException("session_id does not match FIX CompIDs");
      }
    }
  }

  /** Creates the legacy v1 representation, which did not persist full session identity. */
  public FixSessionIdentity(String senderCompId, String targetCompId) {
    this(senderCompId, targetCompId, "");
  }

  /** Returns whether the authenticated complete session identity was durably captured. */
  public boolean hasAuthenticatedSessionId() {
    return !authenticatedSessionId.isBlank();
  }

  /** Rehydrates the exact authenticated session or fails closed for a legacy identity. */
  public SessionID requireAuthenticatedSessionId() {
    if (!hasAuthenticatedSessionId()) {
      throw new IllegalStateException("WAL record lacks authenticated FIX session identity");
    }
    return new SessionID(authenticatedSessionId);
  }
}
