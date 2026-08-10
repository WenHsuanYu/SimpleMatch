package com.simplematch.marketreference;

import java.util.List;
import java.util.Objects;

/** A reusable price-grid definition referenced by one or more market rules. */
public record TickTableDefinition(String tickTableId, List<TickBandDefinition> bands) {
  /** Validates an ordered price grid with exactly one unbounded final band. */
  public TickTableDefinition {
    if (tickTableId == null || tickTableId.isBlank()) {
      throw new MarketReferenceValidationException("tick table id is required");
    }
    bands = List.copyOf(Objects.requireNonNull(bands, "tick table bands are required"));
    if (bands.isEmpty()) {
      throw new MarketReferenceValidationException("tick table must contain at least one band");
    }
    Long previousUpperExclusive = null;
    for (int index = 0; index < bands.size(); index++) {
      final TickBandDefinition band = bands.get(index);
      final boolean finalBand = index == bands.size() - 1;
      if (finalBand != (band.upperExclusiveUnits() == null)) {
        throw new MarketReferenceValidationException("only the final tick band may be unbounded");
      }
      if (previousUpperExclusive != null
          && band.upperExclusiveUnits() != null
          && band.upperExclusiveUnits() <= previousUpperExclusive) {
        throw new MarketReferenceValidationException("tick-band boundaries must increase");
      }
      previousUpperExclusive = band.upperExclusiveUnits();
    }
  }
}
