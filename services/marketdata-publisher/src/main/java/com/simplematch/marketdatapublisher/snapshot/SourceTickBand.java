package com.simplematch.marketdatapublisher.snapshot;

/** Raw source price band retained as decimal text until import normalization. */
record SourceTickBand(String upperExclusive, String tickSize) {}
