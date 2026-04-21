package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import quickfix.SessionID;

public final class OrderSessionState {
  private final SessionID sessionId;
  private final String orderId;
  private final String accountId;
  private final String clientOrderId;
  private final String symbol;
  private final Side side;
  private final String quantity;

  private volatile char currentOrdStatus;
  private volatile CancelRequestState lastCancelRequest;

  public OrderSessionState(
      SessionID sessionId,
      String orderId,
      String accountId,
      String clientOrderId,
      String symbol,
      Side side,
      String quantity,
      char currentOrdStatus) {
    this.sessionId = sessionId;
    this.orderId = orderId;
    this.accountId = accountId;
    this.clientOrderId = clientOrderId;
    this.symbol = symbol;
    this.side = side;
    this.quantity = quantity;
    this.currentOrdStatus = currentOrdStatus;
  }

  public SessionID sessionId() {
    return sessionId;
  }

  public String orderId() {
    return orderId;
  }

  public String accountId() {
    return accountId;
  }

  public String clientOrderId() {
    return clientOrderId;
  }

  public String symbol() {
    return symbol;
  }

  public Side side() {
    return side;
  }

  public String quantity() {
    return quantity;
  }

  public char currentOrdStatus() {
    return currentOrdStatus;
  }

  public void currentOrdStatus(char currentOrdStatus) {
    this.currentOrdStatus = currentOrdStatus;
  }

  public CancelRequestState lastCancelRequest() {
    return lastCancelRequest;
  }

  public void lastCancelRequest(CancelRequestState lastCancelRequest) {
    this.lastCancelRequest = lastCancelRequest;
  }

  public record CancelRequestState(String cancelClientOrderId, String originalClientOrderId) {
  }
}