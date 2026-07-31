package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.SessionID;

/**
 * Holds immutable FIX session context and an immutable lifecycle snapshot for one gateway order.
 */
public record OrderSessionState(
    SessionID sessionId,
    String orderId,
    String accountId,
    String clOrdId,
    String symbol,
    Side side,
    String quantity,
    OrderSessionLifecycle lifecycle) {

  /** Returns this state with the supplied immutable lifecycle snapshot. */
  OrderSessionState withLifecycle(OrderSessionLifecycle updatedLifecycle) {
    return new OrderSessionState(
        sessionId,
        orderId,
        accountId,
        clOrdId,
        symbol,
        side,
        quantity,
        updatedLifecycle);
  }

  /** Captures the client identifiers that correlate an outstanding cancel request. */
  public record CancelRequestState(String cancelClOrdId, String origClOrdId) {}
}
