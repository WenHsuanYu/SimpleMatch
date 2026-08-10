package com.simplematch.marketreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Objects;

/** Verifies externally supplied artifact bytes before a Risk or Matching startup consumes them. */
public final class MarketReferenceArtifactStartupValidator {
  private final MarketReferenceArtifactCodec codec;

  /** Creates a startup validator with a caller-controlled JSON implementation. */
  public MarketReferenceArtifactStartupValidator(ObjectMapper objectMapper) {
    this.codec = new MarketReferenceArtifactCodec(objectMapper);
  }

  /**
   * Checks the external checksum before parsing and returns the final artifact with its identity.
   *
   * @param artifactBytes exact mounted {@code market_reference.json} bytes
   * @param externalChecksum separately mounted lowercase SHA-256 checksum
   * @param expectedTradingDay configured Asia/Taipei trading day
   * @return validated startup artifact and immutable identity
   */
  public VerifiedMarketReferenceArtifact validate(
      byte[] artifactBytes, String externalChecksum, LocalDate expectedTradingDay) {
    final MarketReferenceArtifact artifact =
        codec.readVerified(
            artifactBytes,
            externalChecksum,
            Objects.requireNonNull(expectedTradingDay, "expected trading day is required"));
    return new VerifiedMarketReferenceArtifact(
        artifact, ArtifactIdentity.of(artifact.metadata().tradingDay(), externalChecksum));
  }
}
