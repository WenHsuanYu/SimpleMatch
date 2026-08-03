package com.simplematch.quickfixgateway.wal;

/** Opaque original FIX text retained for gateway audit and recovery. */
public record RawFixMessage(String value) {
  /** Requires the original FIX text to be retained. */
  public RawFixMessage {
    value = WalValidation.nonBlankText(value, "raw_fix");
  }
}
