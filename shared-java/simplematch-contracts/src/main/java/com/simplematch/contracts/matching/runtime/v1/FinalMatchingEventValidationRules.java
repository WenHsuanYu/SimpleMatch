package com.simplematch.contracts.matching.runtime.v1;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import java.util.UUID;

/** Shared scalar and identity rules used by final Matching Event payload validation. */
final class FinalMatchingEventValidationRules {
  private FinalMatchingEventValidationRules() {}

  static void validateParticipant(String orderId, String accountId) {
    requireUuid(orderId, "orderId");
    requireUuid(accountId, "accountId");
  }

  static void validateInstrument(String venueMic, String symbol) {
    require(!venueMic.isBlank(), "venueMic must not be blank");
    require(!symbol.isBlank(), "symbol must not be blank");
  }

  static void requireKnownSide(Side side) {
    require(side == Side.SIDE_BUY || side == Side.SIDE_SELL, "side must be BUY or SELL");
  }

  static void requireSha256(ByteString value, String name) {
    require(value != null && value.size() == 32, name + " must contain exactly 32 bytes");
  }

  static void requireCanonicalHex(String value, String name) {
    require(
        value != null && value.matches("[0-9a-f]{64}"), name + " must be lowercase SHA-256 hex");
  }

  static void requireUuid(String value, String name) {
    try {
      UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(name + " must be a UUID", invalid);
    }
  }

  static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
