package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independently bindable stable market-wide default capability. */
@ConfigurationProperties("simplematch.market")
public record MarketProperties(String currency, String timeZone) {
  /** Normalizes absent market settings to Taiwan market defaults. */
  public MarketProperties {
    currency = PlatformPropertyDefaults.string(currency, "TWD");
    timeZone = PlatformPropertyDefaults.string(timeZone, "Asia/Taipei");
  }

  static MarketProperties defaults() {
    return new MarketProperties(null, null);
  }
}
