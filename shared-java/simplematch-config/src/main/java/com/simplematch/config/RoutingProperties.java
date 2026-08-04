package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently bindable routing-policy snapshot capability.
 *
 * @param snapshotPath classpath or filesystem path to the routing snapshot
 */
@ConfigurationProperties("simplematch.routing")
public record RoutingProperties(String snapshotPath) {
  /** Normalizes an absent routing snapshot path to the bundled classpath resource. */
  public RoutingProperties {
    snapshotPath =
        PlatformPropertyDefaults.string(
            snapshotPath, "classpath:routing/orders-validated.snapshot.json");
  }

  static RoutingProperties defaults() {
    return new RoutingProperties(null);
  }
}
