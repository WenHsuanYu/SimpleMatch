package com.simplematch.marketdatapublisher.snapshot;

import java.util.List;

/** Semantic source document produced by the flat market-source codec. */
record SourceDocument(
    String sourceIdentity,
    long sourceTimestampUnixMs,
    String tradingDay,
    List<String> holidays,
    List<SourceInstrument> instruments) {
  SourceDocument {
    holidays = holidays == null ? List.of() : List.copyOf(holidays);
    instruments = instruments == null ? List.of() : List.copyOf(instruments);
  }
}
