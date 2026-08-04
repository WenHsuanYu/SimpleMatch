package com.simplematch.quickfixgateway.fix;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import quickfix.SessionID;

/** Keeps one active gateway owner for each FIX session in this gateway process. */
public final class FixSessionOwnership {
  private final ConcurrentMap<SessionID, String> owners = new ConcurrentHashMap<>();

  /** Attempts to claim a session for an owner; repeated claims by that owner are idempotent. */
  public boolean tryClaim(SessionID sessionId, String ownerId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(ownerId, "ownerId");
    final String existingOwner = owners.putIfAbsent(sessionId, ownerId);
    return existingOwner == null || existingOwner.equals(ownerId);
  }

  /** Returns whether the supplied owner currently owns the session. */
  public boolean isOwnedBy(SessionID sessionId, String ownerId) {
    return ownerId.equals(owners.get(sessionId));
  }

  /** Releases a session only when the caller still owns it. */
  public void release(SessionID sessionId, String ownerId) {
    owners.remove(sessionId, ownerId);
  }
}
