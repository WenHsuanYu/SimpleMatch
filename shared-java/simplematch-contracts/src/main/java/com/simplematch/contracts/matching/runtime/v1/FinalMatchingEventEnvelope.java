package com.simplematch.contracts.matching.runtime.v1;

import com.google.protobuf.InvalidProtocolBufferException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

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

  /** Returns the stable 32-byte binary event identity for durable storage and transport checks. */
  public byte[] eventIdBytes() {
    return event.getEventId().toByteArray();
  }

  /** Renders the binary event identity for logs and text-only local persistence fields. */
  public String eventIdHex() {
    return HexFormat.of().formatHex(eventIdBytes());
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
}
