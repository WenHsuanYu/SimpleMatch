package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime identity and capability switches owned by the QuickFIX gateway. */
@ConfigurationProperties("simplematch.quickfix-gateway")
public record QuickFixGatewayRuntimeProperties(
    String ownerId,
    Boolean acceptorEnabled,
    Boolean dataPlaneEnabled,
    Boolean compatibilityPublishEnabled,
    Boolean replayEnabled) {
  /** Normalizes absent runtime settings to the gateway's compatibility defaults. */
  public QuickFixGatewayRuntimeProperties {
    ownerId = defaultString(ownerId, "quickfix-gateway-0");
    acceptorEnabled = defaultBoolean(acceptorEnabled, true);
    dataPlaneEnabled = defaultBoolean(dataPlaneEnabled, true);
    compatibilityPublishEnabled = defaultBoolean(compatibilityPublishEnabled, false);
    replayEnabled = defaultBoolean(replayEnabled, true);
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Boolean defaultBoolean(Boolean value, boolean fallback) {
    return value == null ? Boolean.valueOf(fallback) : value;
  }
}
