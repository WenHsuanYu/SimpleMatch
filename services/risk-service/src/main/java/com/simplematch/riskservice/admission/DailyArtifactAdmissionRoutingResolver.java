package com.simplematch.riskservice.admission;

import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.MarketReferenceValidationException;
import com.simplematch.marketreference.RoutingAssignment;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves admissions only from the single final Market Reference Artifact loaded at startup. */
public final class DailyArtifactAdmissionRoutingResolver implements AdmissionRoutingPolicyResolver {
  private final VerifiedMarketReferenceArtifact artifact;
  private final Map<InstrumentRef, Integer> partitions;

  /** Freezes the artifact assignments into one read-only instrument-to-partition lookup. */
  public DailyArtifactAdmissionRoutingResolver(VerifiedMarketReferenceArtifact artifact) {
    this.artifact = Objects.requireNonNull(artifact, "artifact");
    final Map<InstrumentRef, Integer> routes = new LinkedHashMap<>();
    for (RoutingAssignment assignment : artifact.artifact().routingPolicy().assignments()) {
      routes.put(assignment.instrument(), assignment.partitionId());
    }
    this.partitions = Map.copyOf(routes);
  }

  /** Resolves and persists one exact artifact route without a Kafka, database, or hash fallback. */
  @Override
  public AdmissionDeliveryRoute resolve(AdmissionCommand command, Instant at) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(at, "at");
    if (!artifact.identity().tradingDay().equals(command.order().tradingDay())) {
      throw new AdmissionValidationException(
          AdmissionFailure.routingPolicyUnavailable(
              "daily artifact trading day does not match the admission"));
    }
    final InstrumentRef instrument;
    try {
      instrument =
          new InstrumentRef(
              command.order().instrument().venueMic().value(),
              command.order().instrument().symbol().value());
    } catch (MarketReferenceValidationException invalidInstrument) {
      throw new AdmissionValidationException(
          AdmissionFailure.routingInstrumentNotAssigned(
              "admission instrument is not present in the daily artifact"));
    }
    final Integer partition = partitions.get(instrument);
    if (partition == null) {
      throw new AdmissionValidationException(
          AdmissionFailure.routingInstrumentNotAssigned(
              "daily artifact has no routing assignment for the instrument"));
    }
    return AdmissionDeliveryRoute.assigned(
        artifact.identity(), artifact.artifact().routingPolicy().algorithmVersion(), partition);
  }
}
