package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.SessionID;

/**
 * Holds immutable FIX session context, order facts, and lifecycle for one gateway order.
 *
 * @param sessionId FIX session identifier that owns the order.
 * @param accountId Account that submitted the order.
 * @param order Existing gateway-local FIX order snapshot.
 * @param lifecycle Immutable status and cancel-correlation snapshot.
 */
public record OrderSessionState(
    SessionID sessionId,
    String accountId,
    FixOrderSnapshot order,
    OrderSessionLifecycle lifecycle) {

  /** Returns the durable order identity retained in the FIX order snapshot. */
  public String orderId() {
    return order.orderId().value();
  }

  /** Returns the current client order identity retained in the FIX order snapshot. */
  public String clOrdId() {
    return order.clientOrderId().value();
  }

  /** Returns the instrument symbol retained in the FIX order snapshot. */
  public String symbol() {
    return order.symbol().value();
  }

  /** Returns the FIX order side retained in the FIX order snapshot. */
  public Side side() {
    return order.side();
  }

  /** Returns the original order quantity text retained in the FIX order snapshot. */
  public String quantity() {
    return order.quantity().value();
  }

  /** Returns this state with the supplied immutable lifecycle snapshot. */
  OrderSessionState withLifecycle(OrderSessionLifecycle updatedLifecycle) {
    return new OrderSessionState(sessionId, accountId, order, updatedLifecycle);
  }

  /**
   * Captures the client identifiers that correlate an outstanding cancel request.
   *
   * @param cancelClOrdId Client order identifier assigned to the cancel request.
   * @param origClOrdId Client order identifier of the order being cancelled.
   */
  public record CancelRequestState(String cancelClOrdId, String origClOrdId) {}
}
