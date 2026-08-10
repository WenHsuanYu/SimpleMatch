package com.simplematch.marketreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StableRoutingAllocatorTest {
  private final StableRoutingAllocator allocator = new StableRoutingAllocator();

  @DisplayName("baseline sorting uses venue, symbol, least load, and the lowest partition tie break")
  @Test
  void createsDeterministicBaselineAssignments() {
    final RoutingAllocationResult result =
        allocator.allocate(
            List.of(eligible("ROCO", "6488"), eligible("XTAI", "2330"), eligible("XTAI", "1101")),
            null);

    assertThat(result.routingPolicy().assignments())
        .containsExactly(
            new RoutingAssignment(new InstrumentRef("ROCO", "6488"), 0),
            new RoutingAssignment(new InstrumentRef("XTAI", "1101"), 1),
            new RoutingAssignment(new InstrumentRef("XTAI", "2330"), 2));
    assertThat(result.partitionLoads()).containsEntry(0, 1).containsEntry(14, 0);
  }

  @DisplayName("an incremental build retains existing routes and assigns only new eligible instruments")
  @Test
  void retainsPriorAssignmentWhenNewInstrumentIsAdded() {
    final RoutingPolicy previous =
        new RoutingPolicy(
            "stable-least-loaded-v1",
            15,
            150,
            List.of(new RoutingAssignment(new InstrumentRef("XTAI", "2330"), 9)));

    final RoutingAllocationResult result =
        allocator.allocate(List.of(eligible("XTAI", "2330"), eligible("ROCO", "6488")), previous);

    assertThat(result.routingPolicy().assignments())
        .containsExactly(
            new RoutingAssignment(new InstrumentRef("ROCO", "6488"), 0),
            new RoutingAssignment(new InstrumentRef("XTAI", "2330"), 9));
    assertThat(result.routeChanges())
        .containsExactly(new RouteChange(new InstrumentRef("ROCO", "6488"), null, 0));
  }

  @DisplayName("more than the fixed fleet capacity fails with an actionable diagnostic")
  @Test
  void rejectsEligibleUniverseBeyondFleetCapacity() {
    final List<ArtifactInstrument> instruments =
        java.util.stream.IntStream.range(0, 2_251)
            .mapToObj(index -> eligible("XTAI", "%04d".formatted(index)))
            .toList();

    assertThatThrownBy(() -> allocator.allocate(instruments, null))
        .isInstanceOf(MarketReferenceValidationException.class)
        .hasMessageContaining("2,250")
        .hasMessageContaining("2,251");
  }

  private ArtifactInstrument eligible(String venueMic, String symbol) {
    return new ArtifactInstrument(
        new InstrumentRef(venueMic, symbol),
        InstrumentEligibility.ELIGIBLE,
        null,
        "regular-board-common-stock",
        10_000_000L,
        9_000_000L,
        11_000_000L);
  }
}
