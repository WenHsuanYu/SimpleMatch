package com.simplematch.riskservice.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete immutable Risk-local projection of one published Routing Policy. */
public record RoutingPolicyProjection(
    RoutingPolicyProjectionIdentity identity,
    RoutingPolicyProjectionInterval effectiveInterval,
    RoutingPolicyPartitionTopology topology,
    List<RoutingPolicyAssignment> assignments) {
  private static final Comparator<RoutingPolicyAssignment> ASSIGNMENT_ORDER =
      Comparator.comparing((RoutingPolicyAssignment assignment) -> assignment.instrument().symbol())
          .thenComparing(assignment -> assignment.instrument().venueMic());

  /** Validates complete assignment state and freezes deterministic local lookup order. */
  public RoutingPolicyProjection {
    Objects.requireNonNull(identity, "routing policy identity");
    Objects.requireNonNull(effectiveInterval, "effective interval");
    Objects.requireNonNull(topology, "partition topology");
    if (assignments == null || assignments.isEmpty()) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy projection must contain assignments");
    }
    final List<RoutingPolicyAssignment> ordered = new ArrayList<>(assignments);
    final Set<RoutingInstrument> instruments = new HashSet<>();
    for (RoutingPolicyAssignment assignment : ordered) {
      Objects.requireNonNull(assignment, "routing policy assignment");
      if (!instruments.add(assignment.instrument())) {
        throw new RoutingPolicyProjectionValidationException(
            "routing policy projection contains a duplicate instrument: "
                + assignment.instrument());
      }
      if (!topology.contains(assignment.routingPartition())) {
        throw new RoutingPolicyProjectionValidationException(
            "routing partition is outside the declared topology: "
                + assignment.routingPartition());
      }
    }
    ordered.sort(ASSIGNMENT_ORDER);
    assignments = List.copyOf(ordered);
  }

  /** Resolves a known instrument without a hash, default, or remote fallback. */
  public int partitionFor(RoutingInstrument instrument) {
    Objects.requireNonNull(instrument, "instrument");
    return assignments.stream()
        .filter(assignment -> assignment.instrument().equals(instrument))
        .mapToInt(RoutingPolicyAssignment::routingPartition)
        .findFirst()
        .orElseThrow(
            () ->
                new RoutingPolicyProjectionValidationException(
                    "routing policy has no assignment for instrument: " + instrument));
  }

  /** Resolves an instrument to the policy identity and partition that must travel together. */
  public RoutingPolicyResolution resolve(RoutingInstrument instrument) {
    return new RoutingPolicyResolution(identity.routingPolicyId(), partitionFor(instrument));
  }

  /** Returns whether this local projection is authoritative at the supplied instant. */
  public boolean appliesAt(Instant instant) {
    return effectiveInterval.contains(instant);
  }
}
