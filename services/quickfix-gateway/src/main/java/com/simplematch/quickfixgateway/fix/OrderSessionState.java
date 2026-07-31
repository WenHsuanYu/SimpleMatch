package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.SessionID;

/** Holds immutable FIX session context and the mutable lifecycle for one gateway order. */
public record OrderSessionState(
    SessionID sessionId,
    String orderId,
    String accountId,
    String clOrdId,
    String symbol,
    Side side,
    String quantity,
    OrderSessionLifecycle lifecycle) {

  /** Captures the client identifiers that correlate an outstanding cancel request. */
  public record CancelRequestState(String cancelClOrdId, String origClOrdId) {}
}
