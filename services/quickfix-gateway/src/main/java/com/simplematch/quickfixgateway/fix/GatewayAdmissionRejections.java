package com.simplematch.quickfixgateway.fix;

/** Renders stable FIX validation failures from the gate's domain state. */
final class GatewayAdmissionRejections {
  private GatewayAdmissionRejections() {}

  /** Returns the rejection sent for a blocked new order. */
  static FixInboundValidationFailure forNewOrder(GatewayAdmissionGate.State state) {
    return forOperation("new order", state);
  }

  /** Returns the rejection sent for a blocked cancellation. */
  static FixInboundValidationFailure forCancellation(GatewayAdmissionGate.State state) {
    return forOperation("cancellation", state);
  }

  private static FixInboundValidationFailure forOperation(
      String operation, GatewayAdmissionGate.State state) {
    return new FixInboundValidationFailure(
        GatewayAdmissionReasons.forState(state),
        operation + " admission is unavailable while gateway state is " + state);
  }
}
