package com.simplematch.quickfixgateway.fix;

/** Stable reason codes shared by Gateway admission state transitions and protocol rejections. */
final class GatewayAdmissionReasons {
  static final String MARKET_OPEN = "MARKET_OPEN";
  static final String MARKET_PRE_OPEN = "MARKET_PRE_OPEN";
  static final String MARKET_INTERRUPTED = "MARKET_INTERRUPTED";
  static final String MARKET_CLOSED = "MARKET_CLOSED";

  private GatewayAdmissionReasons() {}

  /** Returns the protocol-stable reason code for one gateway state. */
  static String forState(GatewayAdmissionGate.State state) {
    return switch (state) {
      case PRE_OPEN -> MARKET_PRE_OPEN;
      case NEW_ORDERS_PAUSED -> "NEW_ORDERS_PAUSED";
      case MARKET_INTERRUPTED -> MARKET_INTERRUPTED;
      case CLOSED -> MARKET_CLOSED;
      case OPEN -> MARKET_OPEN;
    };
  }
}
