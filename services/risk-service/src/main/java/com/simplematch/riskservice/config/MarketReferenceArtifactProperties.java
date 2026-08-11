package com.simplematch.riskservice.config;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mounted final-artifact locations and the Asia/Taipei trading day they must describe. */
@ConfigurationProperties("simplematch.risk-service.market-reference")
public record MarketReferenceArtifactProperties(
    String artifactLocation,
    String checksumLocation,
    LocalDate tradingDay,
    String matchingImageDigest) {
  /** Rejects incomplete startup configuration before Risk can become ready. */
  public MarketReferenceArtifactProperties {
    artifactLocation = requireLocation(artifactLocation, "artifactLocation");
    checksumLocation = requireLocation(checksumLocation, "checksumLocation");
    Objects.requireNonNull(tradingDay, "tradingDay");
    matchingImageDigest = requireImageDigest(matchingImageDigest);
  }

  private static String requireLocation(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requireImageDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("matchingImageDigest must be a canonical sha256 digest");
    }
    return value;
  }
}
