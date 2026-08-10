package com.simplematch.marketreference.builder;

/** Signals that an offline Market Reference build cannot safely produce an artifact. */
public final class MarketReferenceBuildException extends RuntimeException {
  /** Creates a build failure with an actionable message. */
  public MarketReferenceBuildException(String message) {
    super(message);
  }

  /** Creates a build failure preserving its underlying cause. */
  public MarketReferenceBuildException(String message, Throwable cause) {
    super(message, cause);
  }
}
