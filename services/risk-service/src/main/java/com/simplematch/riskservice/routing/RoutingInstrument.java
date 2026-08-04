package com.simplematch.riskservice.routing;

import java.util.Locale;

/** Normalized instrument key used by the Risk-local routing projection. */
public record RoutingInstrument(String symbol, String venueMic) {
  /** Normalizes case and whitespace before the key enters the local projection. */
  public RoutingInstrument {
    symbol = requireText(symbol, "instrument symbol").toUpperCase(Locale.ROOT);
    venueMic = requireText(venueMic, "instrument venue").toUpperCase(Locale.ROOT);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new RoutingPolicyProjectionValidationException(fieldName + " is required");
    }
    return value.trim();
  }
}
