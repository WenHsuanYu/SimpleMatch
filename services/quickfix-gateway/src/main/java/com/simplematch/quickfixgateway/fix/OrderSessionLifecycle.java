package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionType;

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

  /** Returns whether an execution can advance this lifecycle without reordering it. */
  boolean accepts(ExecutionType executionType) {
    if (isTerminal(currentOrdStatus)) {
      return false;
    }
    final int currentRank = rank(currentOrdStatus);
    final int targetRank = rank(targetStatus(executionType));
    return !isTerminal(currentOrdStatus) || targetRank >= currentRank;
  }

  /** Returns the lifecycle after a previously accepted execution has been reported. */
  OrderSessionLifecycle after(ExecutionType executionType) {
    final OrderSessionLifecycle updated = withCurrentOrdStatus(targetStatus(executionType));
    if (executionType == ExecutionType.EXECUTION_TYPE_CANCELED
        || executionType == ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED) {
      return updated.withLastCancelRequest(null);
    }
    return updated;
  }

  private char targetStatus(ExecutionType executionType) {
    return switch (executionType) {
      case EXECUTION_TYPE_PENDING_NEW -> 'A';
      case EXECUTION_TYPE_NEW -> '0';
      case EXECUTION_TYPE_PARTIAL_FILL -> '1';
      case EXECUTION_TYPE_FILL -> '2';
      case EXECUTION_TYPE_CANCELED -> '4';
      case EXECUTION_TYPE_REJECTED -> '8';
      case EXECUTION_TYPE_CANCEL_REJECTED, EXECUTION_TYPE_UNSPECIFIED -> currentOrdStatus;
      default -> currentOrdStatus;
    };
  }

  private boolean isTerminal(char status) {
    return status == '2' || status == '4' || status == '8' || status == 'C';
  }

  private int rank(char status) {
    return switch (status) {
      case 'A' -> 0;
      case '0' -> 1;
      case '1' -> 2;
      case '2', '4', '8', 'C' -> 3;
      default -> -1;
    };
  }
}
