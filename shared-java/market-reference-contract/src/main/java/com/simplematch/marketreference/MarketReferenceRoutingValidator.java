package com.simplematch.marketreference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates complete eligible-to-partition coverage for the fixed Matching fleet. */
final class MarketReferenceRoutingValidator {
  void validate(MarketReferenceArtifact artifact) {
    final Map<InstrumentRef, ArtifactInstrument> instruments = indexedInstruments(artifact);
    final Set<InstrumentRef> routedInstruments = new HashSet<>();
    final int[] loads = new int[artifact.routingPolicy().partitionCount()];
    for (RoutingAssignment assignment : artifact.routingPolicy().assignments()) {
      validateAssignment(assignment, instruments, routedInstruments, loads);
    }
    validateEligibleCoverage(instruments, routedInstruments);
    validateCapacity(artifact.routingPolicy(), loads);
  }

  private Map<InstrumentRef, ArtifactInstrument> indexedInstruments(
      MarketReferenceArtifact artifact) {
    final Map<InstrumentRef, ArtifactInstrument> instruments = new HashMap<>();
    for (ArtifactInstrument instrument : artifact.marketSnapshot().instruments()) {
      instruments.put(instrument.instrument(), instrument);
    }
    return instruments;
  }

  private void validateAssignment(
      RoutingAssignment assignment,
      Map<InstrumentRef, ArtifactInstrument> instruments,
      Set<InstrumentRef> routedInstruments,
      int[] loads) {
    validatePartition(assignment, loads);
    validateRoutedInstrument(assignment, instruments, routedInstruments);
    loads[assignment.partitionId()]++;
  }

  private void validatePartition(RoutingAssignment assignment, int[] loads) {
    if (assignment.partitionId() >= loads.length) {
      throw new MarketReferenceValidationException(
          "routing assignment has an out-of-range partition");
    }
  }

  private void validateRoutedInstrument(
      RoutingAssignment assignment,
      Map<InstrumentRef, ArtifactInstrument> instruments,
      Set<InstrumentRef> routedInstruments) {
    final ArtifactInstrument instrument = instruments.get(assignment.instrument());
    if (instrument == null) {
      throw new MarketReferenceValidationException(
          "routing assignment refers to an unknown instrument");
    }
    if (instrument.eligibility() != InstrumentEligibility.ELIGIBLE) {
      throw new MarketReferenceValidationException(
          "unsupported instrument must not have a route");
    }
    if (!routedInstruments.add(assignment.instrument())) {
      throw new MarketReferenceValidationException("eligible instrument has duplicate routes");
    }
  }

  private void validateEligibleCoverage(
      Map<InstrumentRef, ArtifactInstrument> instruments, Set<InstrumentRef> routedInstruments) {
    for (ArtifactInstrument instrument : instruments.values()) {
      if (instrument.eligibility() == InstrumentEligibility.ELIGIBLE
          && !routedInstruments.contains(instrument.instrument())) {
        throw new MarketReferenceValidationException("eligible instrument is missing a route");
      }
    }
  }

  private void validateCapacity(RoutingPolicy routingPolicy, int[] loads) {
    for (int load : loads) {
      if (load > routingPolicy.maximumInstrumentsPerPartition()) {
        throw new MarketReferenceValidationException(
            "routing partition exceeds its instrument capacity");
      }
    }
  }
}
