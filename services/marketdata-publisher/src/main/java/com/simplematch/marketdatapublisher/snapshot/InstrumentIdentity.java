package com.simplematch.marketdatapublisher.snapshot;

import java.util.Locale;

/** Identifies one instrument at its normalized venue. */
public record InstrumentIdentity(String symbol, String venueMic) {
  /** Validates the symbol and normalizes the source venue code. */
  public InstrumentIdentity {
    if (symbol == null || symbol.isBlank()) {
      throw new MarketSnapshotValidationException("instrument symbol is required");
    }
    venueMic = venueMic == null ? "UNKNOWN" : venueMic.trim().toUpperCase(Locale.ROOT);
    if (venueMic.isBlank()) {
      throw new MarketSnapshotValidationException("instrument venue is required");
    }
  }
}
