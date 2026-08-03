package com.simplematch.marketdatapublisher.snapshot;

import java.util.List;
import java.util.Objects;

/** Semantic source representation rehydrated from one flat external instrument object. */
record SourceInstrument(
    SourceInstrumentIdentity identity,
    SourceInstrumentClassification classification,
    SourceTradingTerms tradingTerms,
    List<SourceTickBand> tickBands) {
  SourceInstrument {
    Objects.requireNonNull(identity, "source instrument identity is required");
    Objects.requireNonNull(classification, "source instrument classification is required");
    Objects.requireNonNull(tradingTerms, "source trading terms are required");
    tickBands = tickBands == null ? List.of() : List.copyOf(tickBands);
  }
}
