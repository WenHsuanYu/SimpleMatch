package com.simplematch.marketreference;

/** Signals a malformed or unsafe Market Reference Artifact contract. */
public final class MarketReferenceValidationException extends IllegalArgumentException {
  /** Creates a validation failure with an actionable explanation. */
  public MarketReferenceValidationException(String message) {
    super(message);
  }

  /** Creates a validation failure with an actionable explanation and root cause. */
  public MarketReferenceValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
