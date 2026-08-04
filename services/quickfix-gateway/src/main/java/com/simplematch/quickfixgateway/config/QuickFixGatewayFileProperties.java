package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** File locations owned by the QuickFIX gateway. */
@ConfigurationProperties("simplematch.quickfix-gateway")
public record QuickFixGatewayFileProperties(String quickfixConfigPath, String walPath) {
  /** Normalizes absent file settings to the gateway's compatibility defaults. */
  public QuickFixGatewayFileProperties {
    quickfixConfigPath = defaultString(quickfixConfigPath, "config/quickfix/acceptor.cfg");
    walPath = defaultString(walPath, "data/quickfix/wal/inbound.wal");
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
