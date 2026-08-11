package com.simplematch.quickfixgateway.operations;

/** Operator or automatic command understood by the single Gateway admission authority. */
public enum GatewayOperation {
  /** Inspects the current domain decision without changing admission. */
  STATUS,
  /** Opens new-order and cancellation admission after all safety checks succeed. */
  OPEN,
  /** Stops new orders while retaining cancellation admission for an active session. */
  PAUSE_NEW_ORDERS,
  /** Stops all admission because correctness is uncertain. */
  INTERRUPT_MARKET,
  /** Closes the current trading day permanently for the process. */
  CLOSE_DAY
}
