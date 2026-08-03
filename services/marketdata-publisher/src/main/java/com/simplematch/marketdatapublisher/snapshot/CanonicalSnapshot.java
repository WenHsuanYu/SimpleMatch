package com.simplematch.marketdatapublisher.snapshot;

import java.util.List;

/** Normalized snapshot envelope passed to the canonical flat-content codec. */
record CanonicalSnapshot(
    String sourceIdentity,
    long sourceTimestampUnixMs,
    String tradingDay,
    List<MarketInstrument> instruments) {
  CanonicalSnapshot {
    instruments = List.copyOf(instruments);
  }
}
