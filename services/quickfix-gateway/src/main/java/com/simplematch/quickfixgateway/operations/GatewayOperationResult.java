package com.simplematch.quickfixgateway.operations;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Instant;

/** Result returned by the Gateway application boundary for one operational command. */
public record GatewayOperationResult(
    GatewayOperation operation,
    boolean accepted,
    GatewayAdmissionGate.State gateState,
    String reason,
    TradingSystemStatus tradingSystemStatus,
    Instant occurredAt) {
  /** Validates the immutable operator-facing result. */
  public GatewayOperationResult {
    operation = OperationalStatusValidation.required(operation, "operation");
    gateState = OperationalStatusValidation.required(gateState, "gateState");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
    tradingSystemStatus =
        OperationalStatusValidation.required(tradingSystemStatus, "tradingSystemStatus");
    occurredAt = OperationalStatusValidation.required(occurredAt, "occurredAt");
  }
}
