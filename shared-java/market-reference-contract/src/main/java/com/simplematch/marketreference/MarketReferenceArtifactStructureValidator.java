package com.simplematch.marketreference;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates source, reusable-rule, and market-fact sections independent of routes. */
final class MarketReferenceArtifactStructureValidator {
  void validate(MarketReferenceArtifact artifact) {
    validateSourceProvenance(artifact.metadata());
    validateMarketRules(artifact.marketRules());
    validateSnapshot(artifact);
  }

  private void validateSourceProvenance(ArtifactMetadata metadata) {
    final Set<String> sourceIds = new HashSet<>();
    for (SourceProvenance source : metadata.sourceProvenance()) {
      if (!sourceIds.add(source.sourceId())) {
        throw new MarketReferenceValidationException(
            "artifact contains duplicate source provenance");
      }
    }
  }

  private void validateMarketRules(MarketRules marketRules) {
    final Set<String> ruleIds = new HashSet<>();
    final Set<String> tickTableIds = tickTableIds(marketRules);
    for (MarketRule rule : marketRules.rules()) {
      validateMarketRule(rule, ruleIds, tickTableIds);
    }
  }

  private Set<String> tickTableIds(MarketRules marketRules) {
    final Set<String> tickTableIds = new HashSet<>();
    for (TickTableDefinition tickTable : marketRules.tickTables()) {
      if (!tickTableIds.add(tickTable.tickTableId())) {
        throw new MarketReferenceValidationException("artifact contains duplicate tick table ids");
      }
    }
    return tickTableIds;
  }

  private void validateMarketRule(
      MarketRule rule, Set<String> ruleIds, Set<String> tickTableIds) {
    if (!ruleIds.add(rule.ruleId())) {
      throw new MarketReferenceValidationException("artifact contains duplicate market-rule ids");
    }
    if (!tickTableIds.contains(rule.tickTableId())) {
      throw new MarketReferenceValidationException(
          "market rule references an unknown tick table");
    }
  }

  private void validateSnapshot(MarketReferenceArtifact artifact) {
    final Set<InstrumentRef> instrumentIds = new HashSet<>();
    final Set<String> marketRuleIds = marketRuleIds(artifact.marketRules());
    for (ArtifactInstrument instrument : artifact.marketSnapshot().instruments()) {
      validateInstrument(artifact.metadata(), instrument, instrumentIds, marketRuleIds);
    }
  }

  private Set<String> marketRuleIds(MarketRules marketRules) {
    return marketRules.rules().stream().map(MarketRule::ruleId).collect(Collectors.toSet());
  }

  private void validateInstrument(
      ArtifactMetadata metadata,
      ArtifactInstrument instrument,
      Set<InstrumentRef> instrumentIds,
      Set<String> marketRuleIds) {
    if (!instrumentIds.add(instrument.instrument())) {
      throw new MarketReferenceValidationException(
          "artifact contains duplicate instrument identities");
    }
    if (instrument.eligibility() == InstrumentEligibility.ELIGIBLE) {
      validateEligibleInstrument(metadata, instrument, marketRuleIds);
    }
  }

  private void validateEligibleInstrument(
      ArtifactMetadata metadata, ArtifactInstrument instrument, Set<String> marketRuleIds) {
    if (!marketRuleIds.contains(instrument.marketRuleId())) {
      throw new MarketReferenceValidationException(
          "eligible instrument references an unknown market rule");
    }
    if (metadata.releaseState() == ArtifactReleaseState.FINAL) {
      FinalArtifactPriceBand.from(instrument).validate();
    }
  }
}
