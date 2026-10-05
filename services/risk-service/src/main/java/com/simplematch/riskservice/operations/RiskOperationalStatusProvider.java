package com.simplematch.riskservice.operations;

import java.time.Clock;
import java.util.Objects;

/** Supplies fresh Risk observations over one startup-verified daily identity. */
public final class RiskOperationalStatusProvider {
  private final RiskOperationalIdentity identity;
  private final Clock clock;

  /** Creates a provider for the identity Risk admitted at startup. */
  public RiskOperationalStatusProvider(RiskOperationalIdentity identity, Clock clock) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns the current Risk operational fact. */
  public RiskOperationalStatus current() {
    return new RiskOperationalStatus(identity, clock.instant());
  }
}
