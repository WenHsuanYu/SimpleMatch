package com.simplematch.quickfixgateway.wal;

/** Signals that a WAL line does not satisfy the versioned record contract. */
final class WalRecordCodecException extends IllegalArgumentException {
  WalRecordCodecException(String message) {
    super(message);
  }

  WalRecordCodecException(String message, Throwable cause) {
    super(message, cause);
  }
}
