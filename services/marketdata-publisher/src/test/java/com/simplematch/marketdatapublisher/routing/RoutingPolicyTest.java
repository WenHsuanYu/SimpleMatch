package com.simplematch.marketdatapublisher.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingPolicyTest {
  private static final UUID POLICY_ID = UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01");
  private static final UUID SNAPSHOT_ID = UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001");
  private static final InstrumentIdentity AAPL = new InstrumentIdentity("AAPL", "XTAI");
  private static final InstrumentIdentity TSLA = new InstrumentIdentity("TSLA", "ROCO");

  @Test
  @DisplayName("valid policy normalizes assignment order and resolves an instrument route")
  void validPolicyResolvesInstrumentRoute() {
    final RoutingPolicy policy =
        new RoutingPolicy(
            new RoutingPolicyIdentity(POLICY_ID, SNAPSHOT_ID, LocalDate.of(2026, 7, 27)),
            new RoutingPolicyInterval(
                Instant.parse("2026-07-27T00:00:00Z"), Instant.parse("2026-07-27T06:00:00Z")),
            16,
            List.of(
                new RoutingAssignment(TSLA, 11), new RoutingAssignment(AAPL, 7)));

    assertThat(policy.assignments()).extracting(RoutingAssignment::instrument).containsExactly(AAPL, TSLA);
    assertThat(policy.partitionFor(AAPL)).isEqualTo(7);
    assertThat(policy.appliesAt(Instant.parse("2026-07-27T01:00:00Z"))).isTrue();
    assertThat(policy.appliesAt(Instant.parse("2026-07-27T06:00:00Z"))).isFalse();
  }

  @Test
  @DisplayName("policy rejects duplicate instruments and out-of-range partitions")
  void invalidAssignmentsAreRejected() {
    assertThatThrownBy(
            () ->
                new RoutingPolicy(
                    identity(), interval(), 16,
                    List.of(new RoutingAssignment(AAPL, 7), new RoutingAssignment(AAPL, 8))))
        .isInstanceOf(RoutingPolicyValidationException.class)
        .hasMessageContaining("duplicate instrument");

    assertThatThrownBy(
            () ->
                new RoutingPolicy(
                    identity(), interval(), 16, List.of(new RoutingAssignment(AAPL, 16))))
        .isInstanceOf(RoutingPolicyValidationException.class)
        .hasMessageContaining("partition");
  }

  @Test
  @DisplayName("policy rejects an invalid effective interval")
  void invalidIntervalIsRejected() {
    assertThatThrownBy(
            () ->
                new RoutingPolicy(
                    identity(),
                    new RoutingPolicyInterval(
                        Instant.parse("2026-07-27T06:00:00Z"),
                        Instant.parse("2026-07-27T00:00:00Z")),
                    16,
                    List.of(new RoutingAssignment(AAPL, 7))))
        .isInstanceOf(RoutingPolicyValidationException.class)
        .hasMessageContaining("effective interval");
  }

  private RoutingPolicyIdentity identity() {
    return new RoutingPolicyIdentity(POLICY_ID, SNAPSHOT_ID, LocalDate.of(2026, 7, 27));
  }

  private RoutingPolicyInterval interval() {
    return new RoutingPolicyInterval(
        Instant.parse("2026-07-27T00:00:00Z"), Instant.parse("2026-07-27T06:00:00Z"));
  }
}
