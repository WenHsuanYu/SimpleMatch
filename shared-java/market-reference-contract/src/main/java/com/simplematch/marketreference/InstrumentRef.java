package com.simplematch.marketreference;

import java.util.Locale;

/** Stable venue and symbol identity for one Market Reference instrument. */
public record InstrumentRef(String venueMic, String symbol) implements Comparable<InstrumentRef> {
  /** Validates and canonicalizes the venue and source symbol. */
  public InstrumentRef {
    venueMic = canonicalVenue(venueMic);
    symbol = canonicalSymbol(symbol);
  }

  /** Returns deterministic venue-then-symbol ordering. */
  @Override
  public int compareTo(InstrumentRef other) {
    final int venueComparison = venueMic.compareTo(other.venueMic);
    return venueComparison == 0 ? symbol.compareTo(other.symbol) : venueComparison;
  }

  private static String canonicalVenue(String value) {
    if (value == null) {
      throw new MarketReferenceValidationException("instrument venue is required");
    }
    final String canonical = value.trim().toUpperCase(Locale.ROOT);
    if (!canonical.equals("XTAI") && !canonical.equals("ROCO")) {
      throw new MarketReferenceValidationException("instrument venue must be XTAI or ROCO");
    }
    return canonical;
  }

  private static String canonicalSymbol(String value) {
    if (value == null) {
      throw new MarketReferenceValidationException("instrument symbol is required");
    }
    final String canonical = value.trim().toUpperCase(Locale.ROOT);
    if (!canonical.matches("[A-Z0-9]{1,12}")) {
      throw new MarketReferenceValidationException(
          "instrument symbol must be 1 to 12 alphanumeric characters");
    }
    return canonical;
  }
}
