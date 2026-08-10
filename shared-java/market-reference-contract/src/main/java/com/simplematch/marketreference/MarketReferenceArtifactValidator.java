package com.simplematch.marketreference;

import java.time.LocalDate;
import java.util.Objects;

/** Validates the startup-safe Market Reference Artifact contract. */
public final class MarketReferenceArtifactValidator {
  private final MarketReferenceArtifactStructureValidator structureValidator;
  private final MarketReferenceRoutingValidator routingValidator;

  /** Creates a validator for the fixed Phase 1 artifact schema and topology. */
  public MarketReferenceArtifactValidator() {
    this.structureValidator = new MarketReferenceArtifactStructureValidator();
    this.routingValidator = new MarketReferenceRoutingValidator();
  }

  /**
   * Validates an artifact for its declared release state.
   *
   * @param artifact artifact whose internal contract is validated
   */
  public void validate(MarketReferenceArtifact artifact) {
    Objects.requireNonNull(artifact, "artifact is required");
    structureValidator.validate(artifact);
    requireAlgorithmVersionAgreement(artifact);
    routingValidator.validate(artifact);
  }

  /**
   * Validates an artifact is a final artifact for the expected Asia/Taipei trading day.
   *
   * @param artifact artifact to validate
   * @param expectedTradingDay expected configured trading day
   */
  public void validateFinal(MarketReferenceArtifact artifact, LocalDate expectedTradingDay) {
    validate(artifact);
    requireFinalRelease(artifact);
    requireExpectedTradingDay(artifact, expectedTradingDay);
  }

  private void requireFinalRelease(MarketReferenceArtifact artifact) {
    if (artifact.metadata().releaseState() != ArtifactReleaseState.FINAL) {
      throw new MarketReferenceValidationException(
          "preliminary artifacts cannot be used at startup");
    }
  }

  private void requireAlgorithmVersionAgreement(MarketReferenceArtifact artifact) {
    if (!artifact.metadata().routingAlgorithmVersion()
        .equals(artifact.routingPolicy().algorithmVersion())) {
      throw new MarketReferenceValidationException(
          "artifact metadata and routing policy disagree on the routing algorithm version");
    }
  }

  private void requireExpectedTradingDay(
      MarketReferenceArtifact artifact, LocalDate expectedTradingDay) {
    if (!artifact.metadata().tradingDay().equals(expectedTradingDay)) {
      throw new MarketReferenceValidationException(
          "artifact trading day does not match the expected day");
    }
  }
}
