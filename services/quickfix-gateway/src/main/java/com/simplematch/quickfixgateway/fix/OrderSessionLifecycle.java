package com.simplematch.quickfixgateway.fix;

/**
 * Provides the immutable FIX lifecycle values associated with one order session.
 *
 * @param currentOrdStatus the current FIX order status
 * @param lastCancelRequest the outstanding cancel-request correlation values, or {@code null}
 */
public record OrderSessionLifecycle(
    char currentOrdStatus, OrderSessionState.CancelRequestState lastCancelRequest) {

  /**
   * Creates immutable lifecycle values from the supplied FIX status and cancel-request correlation.
   *
   * @param currentOrdStatus the current FIX order status
   * @param lastCancelRequest the outstanding cancel-request correlation values, or {@code null}
   */
  public OrderSessionLifecycle {}

  /**
   * Creates lifecycle values without an outstanding cancel request.
   *
   * @param currentOrdStatus the current FIX order status
   */
  public OrderSessionLifecycle(char currentOrdStatus) {
    this(currentOrdStatus, null);
  }

  /**
   * Returns this lifecycle with the supplied current FIX order status.
   *
   * @param updatedOrdStatus the replacement FIX order status
   * @return an immutable lifecycle snapshot with the replacement status
   */
  OrderSessionLifecycle withCurrentOrdStatus(char updatedOrdStatus) {
    return new OrderSessionLifecycle(updatedOrdStatus, lastCancelRequest);
  }

  /**
   * Returns this lifecycle with the supplied cancel-request correlation values.
   *
   * @param updatedCancelRequest the replacement correlation values, or {@code null}
   * @return an immutable lifecycle snapshot with the replacement correlation values
   */
  OrderSessionLifecycle withLastCancelRequest(
      OrderSessionState.CancelRequestState updatedCancelRequest) {
    return new OrderSessionLifecycle(currentOrdStatus, updatedCancelRequest);
  }
}
