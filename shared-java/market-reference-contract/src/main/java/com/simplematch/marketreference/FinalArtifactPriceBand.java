package com.simplematch.marketreference;

/** Validated final-release reference and daily limit prices for one eligible instrument. */
record FinalArtifactPriceBand(long referencePrice, long lowerLimit, long upperLimit) {
  static FinalArtifactPriceBand from(ArtifactInstrument instrument) {
    if (instrument.referencePriceUnits() == null
        || instrument.lowerPriceLimitUnits() == null
        || instrument.upperPriceLimitUnits() == null) {
      throw new MarketReferenceValidationException(
          "final eligible instrument requires price and limits");
    }
    return new FinalArtifactPriceBand(
        instrument.referencePriceUnits(),
        instrument.lowerPriceLimitUnits(),
        instrument.upperPriceLimitUnits());
  }

  void validate() {
    validatePositive();
    validateOrder();
  }

  private void validatePositive() {
    if (referencePrice <= 0 || lowerLimit <= 0 || upperLimit <= 0) {
      throw new MarketReferenceValidationException(
          "final eligible instrument has an invalid price band");
    }
  }

  private void validateOrder() {
    if (lowerLimit >= referencePrice || upperLimit <= referencePrice) {
      throw new MarketReferenceValidationException(
          "final eligible instrument has an invalid price band");
    }
  }
}
