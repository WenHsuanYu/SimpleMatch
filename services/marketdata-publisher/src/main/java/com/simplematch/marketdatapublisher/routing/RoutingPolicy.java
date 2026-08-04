package com.simplematch.marketdatapublisher.routing;

import com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete, immutable Market Reference routing policy for one trading-day interval.
 *
 * <p>The policy is deliberately a semantic aggregate rather than a transport carrier. It owns
 * partition bounds, assignment uniqueness, deterministic ordering, and interval membership before
 * any persistence or protobuf mapping occurs.
 */
public record RoutingPolicy(
    RoutingPolicyIdentity identity,
    RoutingPolicyInterval effectiveInterval,
    int ordersValidatedPartitionCount,
    List<RoutingAssignment> assignments) {
  private static final Comparator<RoutingAssignment> ASSIGNMENT_ORDER =
      Comparator.comparing((RoutingAssignment assignment) -> assignment.instrument().symbol())
          .thenComparing(assignment -> assignment.instrument().venueMic());

  /** Validates and freezes the complete assignment set used by all downstream projections. */
  public RoutingPolicy {
    Objects.requireNonNull(identity, "routing policy identity");
    Objects.requireNonNull(effectiveInterval, "effective interval");
    if (ordersValidatedPartitionCount <= 0) {
      throw new RoutingPolicyValidationException("partition count must be positive");
    }
    if (assignments == null || assignments.isEmpty()) {
      throw new RoutingPolicyValidationException("routing policy must contain assignments");
    }
    final List<RoutingAssignment> ordered = new ArrayList<>(assignments);
    final Set<InstrumentIdentity> instruments = new HashSet<>();
    for (RoutingAssignment assignment : ordered) {
      Objects.requireNonNull(assignment, "routing assignment");
      if (!instruments.add(assignment.instrument())) {
        throw new RoutingPolicyValidationException(
            "routing policy contains a duplicate instrument: " + assignment.instrument());
      }
      if (assignment.routingPartition() >= ordersValidatedPartitionCount) {
        throw new RoutingPolicyValidationException(
            "routing partition exceeds the declared partition count: "
                + assignment.routingPartition());
      }
    }
    ordered.sort(ASSIGNMENT_ORDER);
    assignments = List.copyOf(ordered);
  }

  /** Resolves a known instrument without falling back to hashing or a default partition. */
  public int partitionFor(InstrumentIdentity instrument) {
    Objects.requireNonNull(instrument, "instrument");
    return assignments.stream()
        .filter(assignment -> assignment.instrument().equals(instrument))
        .mapToInt(RoutingAssignment::routingPartition)
        .findFirst()
        .orElseThrow(
            () ->
                new RoutingPolicyValidationException(
                    "routing policy has no assignment for instrument: " + instrument));
  }

  /** Returns whether the policy is authoritative at the supplied instant. */
  public boolean appliesAt(Instant instant) {
    return effectiveInterval.contains(instant);
  }
}
