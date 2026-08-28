package com.simplematch.quickfixgateway.operations;

/** Signals a temporary transport failure while requesting Risk-owned session closure. */
public final class RetryableTradingSessionCloseException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a retryable close failure with its transport cause.
   *
   * @param message stable failure description
   * @param cause underlying transport failure
   */
  public RetryableTradingSessionCloseException(String message, Throwable cause) {
    super(message, cause);
  }
}
