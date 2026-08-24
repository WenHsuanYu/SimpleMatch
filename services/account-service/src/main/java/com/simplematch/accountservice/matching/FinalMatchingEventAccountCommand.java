package com.simplematch.accountservice.matching;

import com.simplematch.accountservice.reservation.MatchingAccountEffect;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Account-owned command for applying one validated final Matching Event delivery. */
public final class FinalMatchingEventAccountCommand {
  private final byte[] eventId;
  private final byte[] payloadSha256;
  private final List<MatchingAccountEffect> effects;

  /** Requires exact identity evidence and the complete ordered Account effects. */
  public FinalMatchingEventAccountCommand(
      byte[] eventId, byte[] payloadSha256, List<MatchingAccountEffect> effects) {
    this.eventId = requireSha256(eventId, "eventId");
    this.payloadSha256 = requireSha256(payloadSha256, "payloadSha256");
    this.effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
  }

  /** Returns the binary Matching Event identity without exposing internal storage. */
  public byte[] eventId() {
    return eventId.clone();
  }

  /** Returns the exact Kafka value fingerprint without exposing internal storage. */
  public byte[] payloadSha256() {
    return payloadSha256.clone();
  }

  /** Returns the ordered Account effects translated at the Kafka seam. */
  public List<MatchingAccountEffect> effects() {
    return effects;
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
    return Arrays.equals(eventId, command.eventId)
        && Arrays.equals(payloadSha256, command.payloadSha256)
        && effects.equals(command.effects);
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(eventId);
    result = 31 * result + Arrays.hashCode(payloadSha256);
    return 31 * result + effects.hashCode();
  }
}
