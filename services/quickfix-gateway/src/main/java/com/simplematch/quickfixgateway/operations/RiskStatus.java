package com.simplematch.quickfixgateway.operations;

import java.time.Instant;

/** Gateway-domain observation of Risk admission availability for one trading session. */
public record RiskStatus(
    OperationalComponentState state, TradingIdentity identity, Instant observedAt, String reason) {
  /** Validates one adapter-provided Risk observation. */
  public RiskStatus {
    state = OperationalStatusValidation.required(state, "state");
    identity = OperationalStatusValidation.required(identity, "identity");
    observedAt = OperationalStatusValidation.required(observedAt, "observedAt");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}
