package com.simplematch.riskservice.operations;

import java.time.Instant;
import java.util.Objects;

/** Fresh Risk availability fact paired with its verified daily identity. */
public record RiskOperationalStatus(RiskOperationalIdentity identity, Instant observedAt) {
  /** Requires both identity and source observation time. */
  public RiskOperationalStatus {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(observedAt, "observedAt");
  }
}
