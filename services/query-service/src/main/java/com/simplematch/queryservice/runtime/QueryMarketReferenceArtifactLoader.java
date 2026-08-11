package com.simplematch.queryservice.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.queryservice.config.QueryServiceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.core.io.ResourceLoader;

/** Loads and verifies the mounted final artifact before it becomes queryable. */
public final class QueryMarketReferenceArtifactLoader {
  private final ResourceLoader resourceLoader;
  private final QueryServiceProperties properties;
  private final MarketReferenceArtifactStartupValidator validator;

  /** Creates an artifact loader with shared canonical market-reference validation. */
  public QueryMarketReferenceArtifactLoader(
      ResourceLoader resourceLoader, ObjectMapper objectMapper, QueryServiceProperties properties) {
    this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.validator = new MarketReferenceArtifactStartupValidator(objectMapper);
  }

  /** Reads exact mounted bytes and validates their external checksum and trading day. */
  public VerifiedMarketReferenceArtifact load(LocalDate expectedTradingDay) {
    try {
      final byte[] artifactBytes =
          resourceLoader
              .getResource(properties.marketReference().artifactLocation())
              .getInputStream()
              .readAllBytes();
      final String checksum =
          new String(
                  resourceLoader
                      .getResource(properties.marketReference().checksumLocation())
                      .getInputStream()
                      .readAllBytes(),
                  StandardCharsets.US_ASCII)
              .trim();
      return validator.validate(artifactBytes, checksum, expectedTradingDay);
    } catch (IOException failure) {
      throw new IllegalStateException("query market-reference artifact could not be read", failure);
    }
  }
}
