package com.simplematch.marketreference;

/** States whether a known market instrument is in the Phase 1 trading universe. */
public enum InstrumentEligibility {
  /** A regular-board XTAI or ROCO common stock with complete trading facts. */
  ELIGIBLE,
  /** A known source instrument deliberately excluded from the Phase 1 universe. */
  UNSUPPORTED
}
