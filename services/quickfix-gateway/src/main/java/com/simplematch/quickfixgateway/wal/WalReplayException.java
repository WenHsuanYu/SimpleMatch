package com.simplematch.quickfixgateway.wal;

/** Reports a WAL replay failure without modifying the durable WAL file. */
public final class WalReplayException extends IllegalStateException {
  private final int lineNumber;

  WalReplayException(int lineNumber, Throwable cause) {
    super(message(lineNumber, cause), cause);
    this.lineNumber = lineNumber;
  }

  /**
   * Returns the one-based physical line that failed replay.
   *
   * @return the physical WAL line number
   */
  public int lineNumber() {
    return lineNumber;
  }

  private static String message(int lineNumber, Throwable cause) {
    final String detail = cause.getMessage();
    return "failed to replay WAL line " + lineNumber + (detail == null ? "" : ": " + detail);
  }
}
