package com.simplematch.quickfixgateway.fix;

import java.util.concurrent.atomic.AtomicReference;

/** Applies an explicit gateway admission policy to new orders and cancellations. */
public final class GatewayAdmissionGate {
  /** States that can prevent a new order or cancellation from entering the durable path. */
  public enum State {
    OPEN,
    ADMISSION_PAUSED,
    MARKET_INTERRUPTED
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

  /** Returns the current gate state. */
  public State state() {
    return state.get();
  }

  /** Pauses both new-order and cancellation admission. */
  public void pauseAdmission() {
    state.set(State.ADMISSION_PAUSED);
  }

  /** Marks the market as interrupted and pauses both durable paths. */
  public void interruptMarket() {
    state.set(State.MARKET_INTERRUPTED);
  }

  /** Reopens both durable paths. */
  public void reopen() {
    state.set(State.OPEN);
  }

  /** Returns the stable protocol rejection for a new order while the gate is closed. */
  FixInboundValidationFailure newOrderFailure() {
    return failure("new order");
  }

  /** Returns the stable protocol rejection for a cancellation while the gate is closed. */
  FixInboundValidationFailure cancelFailure() {
    return failure("cancellation");
  }

  /** Returns whether the supplied operation may enter WAL and Risk admission. */
  boolean allowsAdmission() {
    return state.get() == State.OPEN;
  }

  private FixInboundValidationFailure failure(String operation) {
    final State current = state.get();
    final String reasonCode =
        current == State.MARKET_INTERRUPTED ? "MARKET_INTERRUPTED" : "ADMISSION_PAUSED";
    return new FixInboundValidationFailure(
        reasonCode, operation + " admission is unavailable while gateway state is " + current);
  }
}
