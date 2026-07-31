package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.SessionID;

/**
 * Holds immutable FIX session context and an immutable lifecycle snapshot for one gateway order.
 *
 * @param sessionId FIX session identifier that owns the order.
 * @param orderId Internal order identifier.
 * @param accountId Account that submitted the order.
 * @param clOrdId Current client order identifier.
 * @param symbol Instrument symbol.
 * @param side FIX order side value.
 * @param quantity Original order quantity text.
 * @param lifecycle Immutable status and cancel-correlation snapshot.
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

  /**
   * Captures the client identifiers that correlate an outstanding cancel request.
   *
   * @param cancelClOrdId Client order identifier assigned to the cancel request.
   * @param origClOrdId Client order identifier of the order being cancelled.
   */
  public record CancelRequestState(String cancelClOrdId, String origClOrdId) {}
}
