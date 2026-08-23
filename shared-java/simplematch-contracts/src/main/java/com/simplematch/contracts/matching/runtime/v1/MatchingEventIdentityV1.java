package com.simplematch.contracts.matching.runtime.v1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Computes version-one deterministic Matching event and trade identities. */
public final class MatchingEventIdentityV1 {
  private static final int IDENTITY_VERSION = 1;
  private static final String EVENT_NAMESPACE = "simplematch.event-id.v1";
  private static final String TRADE_NAMESPACE = "simplematch.trade-id.v1";

  private MatchingEventIdentityV1() {}

  /** Returns the identity of one externally published command output. */
  public static byte[] eventId(
      String tradingSessionId, int partitionId, UUID commandId, int outputIndex) {
    return identity(
        EVENT_NAMESPACE, tradingSessionId, partitionId, commandId, outputIndex, "outputIndex");
  }

  /** Returns the identity of one deterministic match produced by a command. */
  public static byte[] tradeId(
      String tradingSessionId, int partitionId, UUID commandId, int matchIndex) {
    return identity(
        TRADE_NAMESPACE, tradingSessionId, partitionId, commandId, matchIndex, "matchIndex");
  }

  private static byte[] identity(
      String namespace,
      String tradingSessionId,
      int partitionId,
      UUID commandId,
      int index,
      String indexName) {
    final String session = Objects.requireNonNull(tradingSessionId, "tradingSessionId");
    final UUID command = Objects.requireNonNull(commandId, "commandId");
    if (session.isBlank()) {
      throw new IllegalArgumentException("tradingSessionId must not be blank");
    }
    if (partitionId < 0) {
      throw new IllegalArgumentException("partitionId must not be negative");
    }
    if (index < 0) {
      throw new IllegalArgumentException(indexName + " must not be negative");
    }

    final MessageDigest digest = sha256();
    updateText(digest, namespace);
    updateInt32(digest, IDENTITY_VERSION);
    updateText(digest, session);
    updateInt32(digest, partitionId);
    updateUuid(digest, command);
    updateInt32(digest, index);
    return digest.digest();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 must be available in the Java runtime", unavailable);
    }
  }

  private static void updateText(MessageDigest digest, String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    updateInt32(digest, bytes.length);
    digest.update(bytes);
  }

  private static void updateInt32(MessageDigest digest, int value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
  }

  private static void updateUuid(MessageDigest digest, UUID value) {
    digest.update(
        ByteBuffer.allocate(2 * Long.BYTES)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array());
  }
}
