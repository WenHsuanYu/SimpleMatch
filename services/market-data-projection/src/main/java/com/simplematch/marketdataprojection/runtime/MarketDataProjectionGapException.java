package com.simplematch.marketdataprojection.runtime;

/** Signals that a non-contiguous source partition requires an explicit projection rebuild. */
public final class MarketDataProjectionGapException extends RuntimeException {
  /** Creates a fail-closed projection gap signal without affecting authoritative matching state. */
  public MarketDataProjectionGapException(String message) {
    super(message);
  }
}
