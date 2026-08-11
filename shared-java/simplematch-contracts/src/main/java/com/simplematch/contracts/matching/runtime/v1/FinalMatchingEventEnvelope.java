package com.simplematch.contracts.matching.runtime.v1;

import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Validated final Matching Event together with the SHA-256 of its exact Kafka value bytes.
 *
 * <p>Critical consumers must construct this envelope before parsing-derived business behavior. The
 * raw hash is deliberately calculated from the received bytes, never from a reserialized Protobuf
 * message.
 */
public final class FinalMatchingEventEnvelope {
  private final MatchingEvent event;
  private final byte[] rawValue;
  private final byte[] payloadSha256;

  /** Defensively owns a validated event and its original delivery bytes. */
  public FinalMatchingEventEnvelope(MatchingEvent event, byte[] rawValue, byte[] payloadSha256) {
    this.event = Objects.requireNonNull(event, "event");
    this.rawValue = Objects.requireNonNull(rawValue, "rawValue").clone();
    this.payloadSha256 = Objects.requireNonNull(payloadSha256, "payloadSha256").clone();
    if (this.payloadSha256.length != 32) {
      throw new IllegalArgumentException("payloadSha256 must contain exactly 32 bytes");
    }
    if (!Arrays.equals(this.payloadSha256, sha256(this.rawValue))) {
      throw new IllegalArgumentException("payloadSha256 must match the exact raw value bytes");
    }
    FinalMatchingEventValidator.validate(this.event);
  }

  /** Parses the final event and fingerprints precisely the value bytes received from Kafka. */
  public static FinalMatchingEventEnvelope parse(byte[] rawValue)
      throws InvalidProtocolBufferException {
    final byte[] owned = Objects.requireNonNull(rawValue, "rawValue").clone();
    // Retain evidence of the exact bytes before Protobuf parsing can reject them.
    final byte[] payloadSha256 = sha256(owned);
    final MatchingEvent event = MatchingEvent.parseFrom(owned);
    return new FinalMatchingEventEnvelope(event, owned, payloadSha256);
  }

  /** Returns the stable 32-byte binary event identity for PostgreSQL storage. */
  public byte[] eventIdBytes() {
    return HexFormat.of().parseHex(event.getEventId());
  }

  /** Returns the validated final Matching Event. */
  public MatchingEvent event() {
    return event;
  }

  /** Returns the received Kafka value bytes without exposing the internal representation. */
  public byte[] rawValue() {
    return rawValue.clone();
  }

  /** Returns the exact-record SHA-256 without exposing the internal representation. */
  public byte[] payloadSha256() {
    return payloadSha256.clone();
  }

  /** Renders a raw value hash for APIs and quarantine diagnostics. */
  public String payloadSha256Hex() {
    return HexFormat.of().formatHex(payloadSha256);
  }

  /** Computes SHA-256 without treating a parsed Protobuf representation as canonical bytes. */
  public static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 must be available in the Java runtime", unavailable);
    }
  }

  /** Computes a deterministic 32-byte identity from length-delimited business identity fields. */
  public static byte[] deterministicIdentity(String namespace, String... values) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 must be available in the Java runtime", unavailable);
    }
    updateLengthDelimited(digest, namespace);
    for (String value : values) {
      updateLengthDelimited(digest, value);
    }
    return digest.digest();
  }

  /**
   * Derives a stable UUID bridge only where a retained legacy local API still requires UUID text.
   */
  public static UUID deterministicUuid(String namespace, String... values) {
    final byte[] identity = deterministicIdentity(namespace, values);
    identity[6] = (byte) ((identity[6] & 0x0F) | 0x50);
    identity[8] = (byte) ((identity[8] & 0x3F) | 0x80);
    return new UUID(
        java.nio.ByteBuffer.wrap(identity, 0, Long.BYTES).getLong(),
        java.nio.ByteBuffer.wrap(identity, Long.BYTES, Long.BYTES).getLong());
  }

  private static void updateLengthDelimited(MessageDigest digest, String value) {
    final byte[] bytes =
        Objects.requireNonNull(value, "identity value").getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
