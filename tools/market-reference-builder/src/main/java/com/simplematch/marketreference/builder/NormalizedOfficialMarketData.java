package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.MarketRules;
import com.simplematch.marketreference.SourceProvenance;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure normalized source data ready for candidate or final artifact construction. */
public record NormalizedOfficialMarketData(
    MarketRules marketRules,
    List<ArtifactInstrument> instruments,
    List<SourceProvenance> sourceProvenance) {
  /** Defensively preserves deterministic instrument and provenance ordering. */
  public NormalizedOfficialMarketData {
    Objects.requireNonNull(marketRules, "market rules are required");
    instruments =
        List.copyOf(
            Objects.requireNonNull(instruments, "instruments are required").stream()
                .sorted(Comparator.comparing(ArtifactInstrument::instrument))
                .toList());
    sourceProvenance =
        List.copyOf(
            Objects.requireNonNull(sourceProvenance, "source provenance is required").stream()
                .sorted(Comparator.comparing(SourceProvenance::sourceId))
                .toList());
  }
}
