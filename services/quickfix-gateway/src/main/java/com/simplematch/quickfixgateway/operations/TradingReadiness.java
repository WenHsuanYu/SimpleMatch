package com.simplematch.quickfixgateway.operations;

/** Gateway-domain decision derived from the complete operational observation. */
public enum TradingReadiness {
  /** Every required component is fresh, consistent, recovered, and caught up. */
  OPEN_ELIGIBLE,
  /** New orders must stop, while an active-session cancellation path may remain available. */
  PAUSE_REQUIRED,
  /** A correctness contradiction requires the market to interrupt. */
  INTERRUPT_REQUIRED
}
