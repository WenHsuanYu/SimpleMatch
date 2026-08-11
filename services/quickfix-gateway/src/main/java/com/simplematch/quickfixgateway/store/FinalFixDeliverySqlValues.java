package com.simplematch.quickfixgateway.store;

import com.simplematch.contracts.common.v2.Side;
import java.util.HexFormat;

/** Converts durable FIX delivery values between their domain and compact SQL representations. */
final class FinalFixDeliverySqlValues {
  private FinalFixDeliverySqlValues() {}

  static byte[] binaryIdentity(String value) {
    return HexFormat.of().parseHex(value);
  }

  static int sideCode(Side side) {
    return side == Side.SIDE_BUY ? 1 : 2;
  }

  static Side side(int code) {
    return switch (code) {
      case 1 -> Side.SIDE_BUY;
      case 2 -> Side.SIDE_SELL;
      default -> throw new IllegalStateException("stored final FIX delivery side is invalid");
    };
  }
}
