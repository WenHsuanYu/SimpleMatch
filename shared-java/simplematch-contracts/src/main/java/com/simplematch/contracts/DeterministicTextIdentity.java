package com.simplematch.contracts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Derives deterministic identities from an ordered sequence of UTF-8 text fields. */
public final class DeterministicTextIdentity {
  private DeterministicTextIdentity() {}

  /** Returns a SHA-256 digest over length-delimited namespace and value fields. */
  public static byte[] sha256(String namespace, String... values) {
    final MessageDigest digest = newSha256();
    updateLengthDelimited(digest, namespace);
    for (String value : values) {
      updateLengthDelimited(digest, value);
    }
    return digest.digest();
  }

  /** Returns a stable UUID derived from the same deterministic text preimage. */
  public static UUID uuid(String namespace, String... values) {
    final byte[] identity = sha256(namespace, values);
    identity[6] = (byte) ((identity[6] & 0x0F) | 0x50);
    identity[8] = (byte) ((identity[8] & 0x3F) | 0x80);
    return new UUID(
        ByteBuffer.wrap(identity, 0, Long.BYTES).getLong(),
        ByteBuffer.wrap(identity, Long.BYTES, Long.BYTES).getLong());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 must be available in the Java runtime", unavailable);
    }
  }

  private static void updateLengthDelimited(MessageDigest digest, String value) {
    final byte[] bytes =
        Objects.requireNonNull(value, "identity value").getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
