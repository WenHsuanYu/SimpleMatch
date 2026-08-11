package com.simplematch.quickfixgateway.operations;

/** Health state reported by one critical operational component. */
public enum OperationalComponentState {
  /** The component has completed its required role for the current session. */
  READY,
  /** The component is operating with a bounded impairment. */
  DEGRADED,
  /** The component cannot currently perform its required role. */
  NOT_READY,
  /** The component has stopped on preserved evidence that requires investigation. */
  QUARANTINED
}
