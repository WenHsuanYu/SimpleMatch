package com.simplematch.marketdataprojection.store;

/** Persisted ordered-input checkpoint for one market-data projection partition. */
record MarketDataProjectionProgress(long lastProcessedOffset, String recoveryState) {}
