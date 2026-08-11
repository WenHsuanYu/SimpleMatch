package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Immutable decision and evidence summary emitted by the Gateway operational domain. */
public record TradingSystemStatus(
    TradingReadiness readiness,
    Optional<TradingIdentity> identity,
    List<String> reasons,
    List<String> warnings,
    Instant evaluatedAt) {
  /** Validates an immutable readiness result that an operator may inspect. */
  public TradingSystemStatus {
    readiness = OperationalStatusValidation.required(readiness, "readiness");
    identity = OperationalStatusValidation.required(identity, "identity");
    reasons = List.copyOf(OperationalStatusValidation.required(reasons, "reasons"));
    warnings = List.copyOf(OperationalStatusValidation.required(warnings, "warnings"));
    evaluatedAt = OperationalStatusValidation.required(evaluatedAt, "evaluatedAt");
  }

  /** Returns whether this decision can participate in an explicit operator open. */
  public boolean isOpenEligible() {
    return readiness == TradingReadiness.OPEN_ELIGIBLE;
  }
}
