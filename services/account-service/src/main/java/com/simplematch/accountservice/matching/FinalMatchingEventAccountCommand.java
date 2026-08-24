package com.simplematch.accountservice.matching;

import com.simplematch.accountservice.reservation.MatchingAccountEffect;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Account-owned command for applying one validated final Matching Event delivery. */
public final class FinalMatchingEventAccountCommand {
  private final EventId eventId;
  private final PayloadFingerprint payloadFingerprint;
  private final List<MatchingAccountEffect> effects;

  /** Requires exact identity evidence and the complete ordered Account effects. */
  public FinalMatchingEventAccountCommand(
      EventId eventId,
      PayloadFingerprint payloadFingerprint,
      List<MatchingAccountEffect> effects) {
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.payloadFingerprint = Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
    this.effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
  }

  /** Returns the typed binary Matching Event identity. */
  public EventId eventId() {
    return eventId;
  }

  /** Returns the typed fingerprint of the exact Kafka record value. */
  public PayloadFingerprint payloadFingerprint() {
    return payloadFingerprint;
  }

  /** Returns the ordered Account effects translated at the Kafka seam. */
  public List<MatchingAccountEffect> effects() {
    return effects;
  }

  /** Binary Matching Event identity used for Account inbox idempotency. */
  public static final class EventId {
    private final byte[] bytes;

    /** Requires one exact 32-byte Matching Event identity. */
    public EventId(byte[] bytes) {
      this.bytes = requireSha256(bytes, "eventId");
    }

    /** Returns the identity bytes without exposing internal storage. */
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof EventId eventId && Arrays.equals(bytes, eventId.bytes));
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }

  /** SHA-256 fingerprint of the exact Kafka record value bytes. */
  public static final class PayloadFingerprint {
    private final byte[] bytes;

    /** Requires one exact 32-byte raw-value SHA-256 fingerprint. */
    public PayloadFingerprint(byte[] bytes) {
      this.bytes = requireSha256(bytes, "payloadFingerprint");
    }

    /** Returns the fingerprint bytes without exposing internal storage. */
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof PayloadFingerprint fingerprint
              && Arrays.equals(bytes, fingerprint.bytes));
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }

  private static byte[] requireSha256(byte[] value, String name) {
    final byte[] owned = Objects.requireNonNull(value, name).clone();
    if (owned.length != 32) {
      throw new IllegalArgumentException(name + " must contain exactly 32 bytes");
    }
    return owned;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof FinalMatchingEventAccountCommand command)) {
      return false;
    }
    return eventId.equals(command.eventId)
        && payloadFingerprint.equals(command.payloadFingerprint)
        && effects.equals(command.effects);
  }

  @Override
  public int hashCode() {
    return Objects.hash(eventId, payloadFingerprint, effects);
  }
}
