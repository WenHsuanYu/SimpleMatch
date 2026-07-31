package com.simplematch.quickfixgateway.fix;

/** Owns mutable execution status and cancel correlation for a single order session. */
final class OrderSessionLifecycle {
  private volatile char currentOrdStatus;
  private volatile OrderSessionState.CancelRequestState lastCancelRequest;

  OrderSessionLifecycle(char currentOrdStatus) {
    this.currentOrdStatus = currentOrdStatus;
  }

  char currentOrdStatus() {
    return currentOrdStatus;
  }

  void currentOrdStatus(char currentOrdStatus) {
    this.currentOrdStatus = currentOrdStatus;
  }

  OrderSessionState.CancelRequestState lastCancelRequest() {
    return lastCancelRequest;
  }

  void lastCancelRequest(OrderSessionState.CancelRequestState lastCancelRequest) {
    this.lastCancelRequest = lastCancelRequest;
  }
}
