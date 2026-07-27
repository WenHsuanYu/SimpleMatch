package com.simplematch.contracts.v2;

import java.util.Locale;

/** Phase-one Taiwan trading venues identified by ISO 10383 MIC. */
public enum VenueMic {
  XTAI,
  ROCO;

  /** Parses a supported venue MIC. */
  public static VenueMic parse(String value) {
    if (value == null || value.isBlank()) {
      throw new DomainValidationException("venue_mic is required");
    }
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new DomainValidationException("venue_mic must be XTAI or ROCO");
    }
  }
}
