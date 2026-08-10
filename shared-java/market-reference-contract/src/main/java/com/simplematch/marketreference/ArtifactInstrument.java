package com.simplematch.marketreference;

import java.util.Objects;

/** Complete known-instrument facts, with routing deliberately held in a separate section. */
public record ArtifactInstrument(
    InstrumentRef instrument,
    InstrumentEligibility eligibility,
    String ineligibilityReason,
    String marketRuleId,
    Long referencePriceUnits,
    Long lowerPriceLimitUnits,
    Long upperPriceLimitUnits) {
  /** Validates the contextual facts that do not depend on final-release state. */
  public ArtifactInstrument {
    Objects.requireNonNull(instrument, "instrument is required");
    Objects.requireNonNull(eligibility, "instrument eligibility is required");
    if (eligibility == InstrumentEligibility.ELIGIBLE) {
      if (marketRuleId == null || marketRuleId.isBlank()) {
        throw new MarketReferenceValidationException("eligible instrument market rule is required");
      }
      if (ineligibilityReason != null && !ineligibilityReason.isBlank()) {
        throw new MarketReferenceValidationException(
            "eligible instrument cannot have an ineligibility reason");
      }
    } else {
      if (ineligibilityReason == null || ineligibilityReason.isBlank()) {
        throw new MarketReferenceValidationException("unsupported instrument reason is required");
      }
      if (marketRuleId != null || referencePriceUnits != null || lowerPriceLimitUnits != null
          || upperPriceLimitUnits != null) {
        throw new MarketReferenceValidationException(
            "unsupported instrument must not contain tradable rule or price facts");
      }
    }
  }
}
