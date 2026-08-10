package com.simplematch.marketreference;

/** One lower-inclusive price band in a reusable tick table. */
public record TickBandDefinition(Long upperExclusiveUnits, long tickSizeUnits) {
  /** Validates the fixed-point tick size and optional upper boundary. */
  public TickBandDefinition {
    if (upperExclusiveUnits != null && upperExclusiveUnits <= 0) {
      throw new MarketReferenceValidationException("tick-band upper boundary must be positive");
    }
    if (tickSizeUnits <= 0) {
      throw new MarketReferenceValidationException("tick-band size must be positive");
    }
  }
}
