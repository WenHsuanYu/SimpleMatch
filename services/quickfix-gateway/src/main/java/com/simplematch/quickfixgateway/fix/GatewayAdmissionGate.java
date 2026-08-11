package com.simplematch.quickfixgateway.fix;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies the explicit trading-day admission policy to FIX new orders and cancellations.
 *
 * <p>The gate deliberately owns only ingress permissions. The operational controller decides when
 * the system is safe to transition it; this type never depends on Kubernetes, Kafka, or component
 * status representations.
 *
 * <p>Its focused methods all update or query the one atomic gate state. Splitting the transition,
 * permission, and protocol-rejection helpers would make one state-machine boundary less explicit.
 */
public final class GatewayAdmissionGate {
  /** Trading-day states with distinct new-order and cancellation permissions. */
  public enum State {
    /** The gateway has started but an operator has not opened the market. */
    PRE_OPEN,
    /** New orders and cancellations may enter the durable path. */
    OPEN,
    /** New orders are paused while cancellation requests remain available. */
    NEW_ORDERS_PAUSED,
    /** An integrity violation requires both new orders and cancellations to stop. */
    MARKET_INTERRUPTED,
    /** The trading day has ended and cannot be reopened by this process. */
    CLOSED
  }

  private final AtomicReference<GateState> gateState =
      new AtomicReference<>(new GateState(State.PRE_OPEN, GatewayAdmissionReasons.MARKET_PRE_OPEN));

  /** Returns the current trading-day gate state. */
  public State state() {
    return gateState.get().state();
  }

  /** Returns the reason associated with the current gate state. */
  public String reason() {
    return gateState.get().reason();
  }

  /**
   * Opens the durable ingress paths unless this process has already closed its trading day.
   *
   * @return {@code true} when the gate is open after this call; {@code false} after a day close
   */
  public boolean open() {
    final GateState next =
        gateState.updateAndGet(
            current ->
                current.state() == State.CLOSED
                    ? current
                    : new GateState(State.OPEN, GatewayAdmissionReasons.MARKET_OPEN));
    return next.state() == State.OPEN;
  }

  /**
   * Stops new orders while retaining the cancellation path during an active trading day.
   *
   * <p>Pre-open, interrupted, and closed states are stricter and are therefore not weakened by a
   * pause request.
   *
   * @param reason stable operational reason for the pause
   */
  public void pauseNewOrders(String reason) {
    final String requiredReason = Objects.requireNonNull(reason, "reason must not be null");
    if (requiredReason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    gateState.updateAndGet(
        current ->
            current.state() == State.OPEN || current.state() == State.NEW_ORDERS_PAUSED
                ? new GateState(State.NEW_ORDERS_PAUSED, requiredReason)
                : current);
  }

  /** Marks the market as interrupted and stops both durable ingress paths. */
  public void interruptMarket() {
    gateState.updateAndGet(
        current ->
            current.state() == State.CLOSED
                ? current
                : new GateState(
                    State.MARKET_INTERRUPTED, GatewayAdmissionReasons.MARKET_INTERRUPTED));
  }

  /** Closes the current trading day permanently for this gateway process. */
  public void closeDay() {
    gateState.set(new GateState(State.CLOSED, GatewayAdmissionReasons.MARKET_CLOSED));
  }

  /** Returns the stable protocol rejection for a new order while the gate is closed. */
  FixInboundValidationFailure newOrderFailure() {
    return GatewayAdmissionRejections.forNewOrder(state());
  }

  /** Returns the stable protocol rejection for a cancellation while the gate is closed. */
  FixInboundValidationFailure cancelFailure() {
    return GatewayAdmissionRejections.forCancellation(state());
  }

  /** Returns whether a new order may enter WAL and Risk admission. */
  boolean allowsNewOrders() {
    return state() == State.OPEN;
  }

  /** Returns whether a cancellation may enter WAL and Risk admission. */
  boolean allowsCancellations() {
    final State current = state();
    return current == State.OPEN || current == State.NEW_ORDERS_PAUSED;
  }

  private record GateState(State state, String reason) {}
}
