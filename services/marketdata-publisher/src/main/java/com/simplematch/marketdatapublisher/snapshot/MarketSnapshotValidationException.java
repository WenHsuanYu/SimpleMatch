package com.simplematch.marketdatapublisher.snapshot;

/** Signals that a market-reference source cannot produce a publishable immutable snapshot. */
public final class MarketSnapshotValidationException extends IllegalArgumentException {
  /** Creates an exception with the rejected source detail. */
  public MarketSnapshotValidationException(String message) {
    super(message);
  }

  /** Creates an exception with the rejected source detail and parsing cause. */
  public MarketSnapshotValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
