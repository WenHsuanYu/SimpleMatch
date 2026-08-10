package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.RouteChange;
import com.simplematch.marketreference.RoutingAllocationResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Produces bounded deterministic operator diffs between an earlier artifact and current build. */
final class ArtifactReviewSummaryFactory {
  private static final int MAXIMUM_SAMPLE_ITEMS = 25;

  ArtifactReviewSummary create(
      MarketReferenceArtifact current,
      MarketReferenceArtifact previous,
      RoutingAllocationResult allocation,
      long artifactSizeBytes,
      String contentSha256,
      ArtifactDeliveryPlan deliveryPlan) {
    final Map<InstrumentRef, ArtifactInstrument> currentInstruments = index(current);
    final Map<InstrumentRef, ArtifactInstrument> previousInstruments = index(previous);
    return new ArtifactReviewSummary(
        count(current, InstrumentEligibility.ELIGIBLE),
        count(current, InstrumentEligibility.UNSUPPORTED),
        additions(currentInstruments, previousInstruments).size(),
        sample(additions(currentInstruments, previousInstruments)),
        removals(currentInstruments, previousInstruments).size(),
        sample(removals(currentInstruments, previousInstruments)),
        eligibilityChanges(currentInstruments, previousInstruments).size(),
        sample(eligibilityChanges(currentInstruments, previousInstruments)),
        allocation.routeChanges().size(),
        sample(allocation.routeChanges().stream().map(this::routeChange).toList()),
        allocation.partitionLoads(),
        artifactSizeBytes,
        contentSha256,
        deliveryPlan == null ? "NOT_DEPLOYABLE" : deliveryPlan.deliveryType().name(),
        validationResults(deliveryPlan));
  }

  private List<String> validationResults(ArtifactDeliveryPlan deliveryPlan) {
    if (deliveryPlan == null) {
      return List.of(
          "OFFICIAL_SOURCES_RECONCILED", "ARTIFACT_VALIDATED", "CONTENT_SHA256_CALCULATED",
          "CANDIDATE_NOT_DEPLOYABLE");
    }
    return List.of(
        "OFFICIAL_SOURCES_RECONCILED",
        "ARTIFACT_VALIDATED",
        "EXTERNAL_CHECKSUM_VERIFIED",
        "DELIVERY_PLAN_VALIDATED");
  }

  private Map<InstrumentRef, ArtifactInstrument> index(MarketReferenceArtifact artifact) {
    if (artifact == null) {
      return Map.of();
    }
    return artifact.marketSnapshot().instruments().stream()
        .collect(Collectors.toMap(ArtifactInstrument::instrument, Function.identity()));
  }

  private int count(MarketReferenceArtifact artifact, InstrumentEligibility eligibility) {
    return (int)
        artifact.marketSnapshot().instruments().stream()
            .filter(instrument -> instrument.eligibility() == eligibility)
            .count();
  }

  private List<String> additions(
      Map<InstrumentRef, ArtifactInstrument> current,
      Map<InstrumentRef, ArtifactInstrument> previous) {
    return current.keySet().stream()
        .filter(key -> !previous.containsKey(key))
        .map(this::identity)
        .toList();
  }

  private List<String> removals(
      Map<InstrumentRef, ArtifactInstrument> current,
      Map<InstrumentRef, ArtifactInstrument> previous) {
    return previous.keySet().stream()
        .filter(key -> !current.containsKey(key))
        .map(this::identity)
        .toList();
  }

  private List<String> eligibilityChanges(
      Map<InstrumentRef, ArtifactInstrument> current,
      Map<InstrumentRef, ArtifactInstrument> previous) {
    return current.entrySet().stream()
        .filter(entry -> previous.containsKey(entry.getKey()))
        .filter(
            entry ->
                entry.getValue().eligibility()
                    != previous.get(entry.getKey()).eligibility())
        .map(
            entry ->
                identity(entry.getKey())
                    + ':'
                    + previous.get(entry.getKey()).eligibility()
                    + "->"
                    + entry.getValue().eligibility())
        .toList();
  }

  private List<String> sample(List<String> values) {
    return values.stream().sorted().limit(MAXIMUM_SAMPLE_ITEMS).toList();
  }

  private String routeChange(RouteChange change) {
    return identity(change.instrument())
        + ':'
        + change.previousPartitionId()
        + "->"
        + change.partitionId();
  }

  private String identity(InstrumentRef instrument) {
    return instrument.venueMic() + ':' + instrument.symbol();
  }
}
