package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Reads the exact mounted daily artifact bytes once and verifies their independently mounted hash.
 */
public final class DailyMarketReferenceArtifactLoader {
  private final ResourceLoader resourceLoader;
  private final MarketReferenceArtifactStartupValidator validator;

  /** Creates the loader over Spring resources and the canonical artifact validator. */
  public DailyMarketReferenceArtifactLoader(
      ResourceLoader resourceLoader, ObjectMapper objectMapper) {
    this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
    this.validator =
        new MarketReferenceArtifactStartupValidator(Objects.requireNonNull(objectMapper));
  }

  /** Loads and verifies the configured final artifact, or rejects Risk startup. */
  public VerifiedMarketReferenceArtifact load(MarketReferenceArtifactProperties properties) {
    Objects.requireNonNull(properties, "properties");
    final Resource artifact = resourceLoader.getResource(properties.artifactLocation());
    final Resource checksum = resourceLoader.getResource(properties.checksumLocation());
    try {
      return validator.validate(
          readRequired(artifact, "market reference artifact"),
          new String(readRequired(checksum, "market reference checksum"), StandardCharsets.US_ASCII)
              .trim(),
          properties.tradingDay());
    } catch (IOException failure) {
      throw new IllegalStateException(
          "unable to read the final market reference artifact", failure);
    }
  }

  private static byte[] readRequired(Resource resource, String name) throws IOException {
    if (!resource.exists()) {
      throw new IllegalStateException(name + " is missing: " + resource.getDescription());
    }
    try (InputStream stream = resource.getInputStream()) {
      return stream.readAllBytes();
    }
  }
}
