package com.simplematch.marketdatapublisher.snapshot;

/** Raw source identity retained until venue eligibility and normalization are evaluated. */
record SourceInstrumentIdentity(String symbol, String venueMic) {}
