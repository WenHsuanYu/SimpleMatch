package com.simplematch.quickfixgateway.fix;

/** Provides the immutable FIX lifecycle values associated with one order session. */
public record OrderSessionLifecycle(
    char currentOrdStatus, OrderSessionState.CancelRequestState lastCancelRequest) {

  /** Creates lifecycle values without an outstanding cancel request. */
  public OrderSessionLifecycle(char currentOrdStatus) {
    this(currentOrdStatus, null);
  }

  /** Returns this lifecycle with the supplied current FIX order status. */
  OrderSessionLifecycle withCurrentOrdStatus(char updatedOrdStatus) {
    return new OrderSessionLifecycle(updatedOrdStatus, lastCancelRequest);
  }

  /** Returns this lifecycle with the supplied cancel-request correlation values. */
  OrderSessionLifecycle withLastCancelRequest(
      OrderSessionState.CancelRequestState updatedCancelRequest) {
    return new OrderSessionLifecycle(currentOrdStatus, updatedCancelRequest);
  }
}
