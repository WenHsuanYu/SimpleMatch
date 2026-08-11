package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;

/** Derives the stable reason returned when an explicit market-open command is refused. */
final class GatewayOpenRejections {
  private GatewayOpenRejections() {}

  static String reason(
      GatewayAdmissionGate.State gateState,
      TradingSystemStatus status,
      int readyChecks,
      int requiredReadyChecks) {
    if (gateState == GatewayAdmissionGate.State.CLOSED) {
      return "TRADING_DAY_CLOSED";
    }
    if (!status.isOpenEligible()) {
      return status.reasons().isEmpty() ? status.readiness().name() : status.reasons().getFirst();
    }
    return "READY_CHECKS_" + readyChecks + "_OF_" + requiredReadyChecks;
  }
}
