package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.SessionID;

/** Holds the FIX session context and current status for one gateway order. */
@SuppressWarnings(
    "PMD.TooManyMethods") // Mutable session state requires explicit synchronized-state accessors.
public final class OrderSessionState {
  private final SessionID sessionId;
  private final String orderId;
  private final String accountId;
  private final String clOrdId;
  private final String symbol;
  private final Side side;
  private final String quantity;

  private volatile char currentOrdStatus;
  private volatile CancelRequestState lastCancelRequest;

  /** Creates the session context captured when a gateway order is admitted. */
  public OrderSessionState(
      SessionID sessionId,
      String orderId,
      String accountId,
      String clOrdId,
      String symbol,
      Side side,
      String quantity,
      char currentOrdStatus) {
    this.sessionId = sessionId;
    this.orderId = orderId;
    this.accountId = accountId;
    this.clOrdId = clOrdId;
    this.symbol = symbol;
    this.side = side;
    this.quantity = quantity;
    this.currentOrdStatus = currentOrdStatus;
  }

  /** Returns the originating FIX session. */
  public SessionID sessionId() {
    return sessionId;
  }

  /** Returns the gateway order identifier. */
  public String orderId() {
    return orderId;
  }

  /** Returns the order's account identifier. */
  public String accountId() {
    return accountId;
  }

  /** Returns the client order identifier. */
  public String clOrdId() {
    return clOrdId;
  }

  /** Returns the requested symbol. */
  public String symbol() {
    return symbol;
  }

  /** Returns the requested order side. */
  public Side side() {
    return side;
  }

  /** Returns the requested quantity. */
  public String quantity() {
    return quantity;
  }

  /** Returns the latest FIX order status. */
  public char currentOrdStatus() {
    return currentOrdStatus;
  }

  /** Updates the latest FIX order status. */
  public void currentOrdStatus(char currentOrdStatus) {
    this.currentOrdStatus = currentOrdStatus;
  }

  /** Returns the latest outstanding cancel request, if any. */
  public CancelRequestState lastCancelRequest() {
    return lastCancelRequest;
  }

  /** Records or clears the outstanding cancel request. */
  public void lastCancelRequest(CancelRequestState lastCancelRequest) {
    this.lastCancelRequest = lastCancelRequest;
  }

  /** Captures the client identifiers that correlate an outstanding cancel request. */
  public record CancelRequestState(String cancelClOrdId, String origClOrdId) {}
}
