package com.simplematch.quickfixgateway.operations;

/** Durable audit outcome for one admission operation. */
public enum GatewayOperationOutcome {
  /** The requested command changed or confirmed the intended gate state. */
  ACCEPTED,
  /** The requested command was refused by safety policy or a terminal day close. */
  REJECTED
}
