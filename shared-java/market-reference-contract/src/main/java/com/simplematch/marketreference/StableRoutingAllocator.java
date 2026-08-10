package com.simplematch.marketreference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Allocates immutable daily routes without moving still-eligible instruments. */
public final class StableRoutingAllocator {
  /** Fixed Phase 1 routing algorithm version. */
  public static final String ALGORITHM_VERSION = "stable-least-loaded-v1";
  /** Fixed number of Phase 1 Matching partitions. */
  public static final int PARTITION_COUNT = 15;
  /** Fixed per-partition instrument order-book capacity. */
  public static final int MAXIMUM_INSTRUMENTS_PER_PARTITION = 150;

  /**
   * Allocates routes for all eligible instruments while retaining current prior routes.
   *
   * @param instruments complete known current universe
   * @param previousPolicy prior approved policy, or {@code null} for the baseline
   * @return complete policy and bounded operator diagnostics
   */
  public RoutingAllocationResult allocate(
      List<ArtifactInstrument> instruments, RoutingPolicy previousPolicy) {
    Objects.requireNonNull(instruments, "current instruments are required");
    final List<ArtifactInstrument> eligibleInstruments =
        instruments.stream()
            .filter(instrument -> instrument.eligibility() == InstrumentEligibility.ELIGIBLE)
            .sorted(Comparator.comparing(ArtifactInstrument::instrument))
            .toList();
    validateEligibleUniverse(eligibleInstruments);

    final Map<InstrumentRef, Integer> previousAssignments = previousAssignments(previousPolicy);
    final int[] partitionLoads = new int[PARTITION_COUNT];
    final Map<InstrumentRef, Integer> assignments = new HashMap<>();
    for (ArtifactInstrument instrument : eligibleInstruments) {
      final Integer priorPartition = previousAssignments.get(instrument.instrument());
      if (priorPartition != null) {
        assignments.put(instrument.instrument(), priorPartition);
        partitionLoads[priorPartition]++;
      }
    }
    validateRetainedLoads(partitionLoads);

    for (ArtifactInstrument instrument : eligibleInstruments) {
      if (!assignments.containsKey(instrument.instrument())) {
        final int partition = leastLoadedPartition(partitionLoads);
        assignments.put(instrument.instrument(), partition);
        partitionLoads[partition]++;
      }
    }

    final List<RoutingAssignment> routes =
        assignments.entrySet().stream()
            .map(entry -> new RoutingAssignment(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(RoutingAssignment::instrument))
            .toList();
    final RoutingPolicy policy =
        new RoutingPolicy(
            ALGORITHM_VERSION, PARTITION_COUNT, MAXIMUM_INSTRUMENTS_PER_PARTITION, routes);
    return new RoutingAllocationResult(
        policy,
        loads(partitionLoads),
        routeChanges(previousAssignments, assignments));
  }

  private void validateEligibleUniverse(List<ArtifactInstrument> eligibleInstruments) {
    final int capacity = PARTITION_COUNT * MAXIMUM_INSTRUMENTS_PER_PARTITION;
    if (eligibleInstruments.size() > capacity) {
      throw new MarketReferenceValidationException(
          "eligible universe has %,d instruments but %d partitions x %d allows %,d"
              .formatted(
                  eligibleInstruments.size(),
                  PARTITION_COUNT,
                  MAXIMUM_INSTRUMENTS_PER_PARTITION,
                  capacity));
    }
    final Set<InstrumentRef> identities = new HashSet<>();
    for (ArtifactInstrument instrument : eligibleInstruments) {
      if (!identities.add(instrument.instrument())) {
        throw new MarketReferenceValidationException(
            "eligible universe contains duplicate instruments");
      }
    }
  }

  private Map<InstrumentRef, Integer> previousAssignments(RoutingPolicy previousPolicy) {
    if (previousPolicy == null) {
      return Map.of();
    }
    validatePreviousTopology(previousPolicy);
    return indexedPreviousAssignments(previousPolicy);
  }

  private void validatePreviousTopology(RoutingPolicy previousPolicy) {
    if (!ALGORITHM_VERSION.equals(previousPolicy.algorithmVersion())
        || previousPolicy.partitionCount() != PARTITION_COUNT
        || previousPolicy.maximumInstrumentsPerPartition() != MAXIMUM_INSTRUMENTS_PER_PARTITION) {
      throw new MarketReferenceValidationException(
          "previous routing policy has an incompatible topology");
    }
  }

  private Map<InstrumentRef, Integer> indexedPreviousAssignments(RoutingPolicy previousPolicy) {
    final Map<InstrumentRef, Integer> assignments = new HashMap<>();
    for (RoutingAssignment assignment : previousPolicy.assignments()) {
      addPreviousAssignment(assignments, assignment);
    }
    return Map.copyOf(assignments);
  }

  private void addPreviousAssignment(
      Map<InstrumentRef, Integer> assignments, RoutingAssignment assignment) {
    if (assignment.partitionId() >= PARTITION_COUNT) {
      throw new MarketReferenceValidationException(
          "previous routing policy has an out-of-range partition");
    }
    if (assignments.put(assignment.instrument(), assignment.partitionId()) != null) {
      throw new MarketReferenceValidationException(
          "previous routing policy contains duplicate routes");
    }
  }

  private void validateRetainedLoads(int[] partitionLoads) {
    for (int load : partitionLoads) {
      if (load > MAXIMUM_INSTRUMENTS_PER_PARTITION) {
        throw new MarketReferenceValidationException("retained routes exceed partition capacity");
      }
    }
  }

  private int leastLoadedPartition(int[] partitionLoads) {
    int chosen = 0;
    for (int partition = 1; partition < partitionLoads.length; partition++) {
      if (partitionLoads[partition] < partitionLoads[chosen]) {
        chosen = partition;
      }
    }
    return chosen;
  }

  private Map<Integer, Integer> loads(int[] partitionLoads) {
    final Map<Integer, Integer> loads = new LinkedHashMap<>();
    for (int partition = 0; partition < partitionLoads.length; partition++) {
      loads.put(partition, partitionLoads[partition]);
    }
    return loads;
  }

  private List<RouteChange> routeChanges(
      Map<InstrumentRef, Integer> previousAssignments, Map<InstrumentRef, Integer> assignments) {
    final Set<InstrumentRef> allInstruments = new HashSet<>();
    allInstruments.addAll(previousAssignments.keySet());
    allInstruments.addAll(assignments.keySet());
    final List<RouteChange> changes = new ArrayList<>();
    for (InstrumentRef instrument : allInstruments.stream().sorted().toList()) {
      final Integer previous = previousAssignments.get(instrument);
      final Integer current = assignments.get(instrument);
      if (!Objects.equals(previous, current)) {
        changes.add(new RouteChange(instrument, previous, current));
      }
    }
    return List.copyOf(changes);
  }
}
