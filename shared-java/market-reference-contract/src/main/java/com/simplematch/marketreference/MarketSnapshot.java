package com.simplematch.marketreference;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** The complete known instrument universe for a single daily artifact. */
public record MarketSnapshot(List<ArtifactInstrument> instruments) {
  /** Preserves deterministic instrument ordering. */
  public MarketSnapshot {
    instruments =
        List.copyOf(
            Objects.requireNonNull(instruments, "market instruments are required").stream()
                .sorted(Comparator.comparing(ArtifactInstrument::instrument))
                .toList());
    if (instruments.isEmpty()) {
      throw new MarketReferenceValidationException(
          "market snapshot must contain at least one instrument");
    }
  }
}
