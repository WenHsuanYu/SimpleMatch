package com.simplematch.riskservice.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class RiskRoutingPolicyReadinessHealthIndicatorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC);

  @DisplayName("Risk readiness fails closed when local policy state is missing")
  @Test
  void isOutOfServiceWhenMissing() {
    final RiskRoutingPolicyReadinessHealthIndicator indicator =
        new RiskRoutingPolicyReadinessHealthIndicator(new StubRepository(), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails()).containsEntry("reason", "MISSING_ROUTING_POLICY");
  }

  @DisplayName("Risk readiness is up only for a complete applicable local projection")
  @Test
  void isUpForApplicablePolicy() {
    final RoutingPolicyProjection policy = policy(16, CLOCK.instant(), CLOCK.instant().plusSeconds(3600));
    final RiskRoutingPolicyReadinessHealthIndicator indicator =
        new RiskRoutingPolicyReadinessHealthIndicator(new StubRepository(policy), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(indicator.health().getDetails())
        .containsEntry("policyId", policy.identity().routingPolicyId().toString());
  }

  @DisplayName("Risk readiness fails closed when the local topology is incompatible")
  @Test
  void isOutOfServiceForTopologyMismatch() {
    final RoutingPolicyProjection policy = policy(15, CLOCK.instant(), CLOCK.instant().plusSeconds(3600));
    final RiskRoutingPolicyReadinessHealthIndicator indicator =
        new RiskRoutingPolicyReadinessHealthIndicator(new StubRepository(policy), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails())
        .containsEntry("reason", "ROUTING_PARTITION_TOPOLOGY_MISMATCH");
  }

  @DisplayName("Risk readiness fails closed after the local policy interval expires")
  @Test
  void isOutOfServiceForExpiredPolicy() {
    final RoutingPolicyProjection policy =
        policy(
            16,
            CLOCK.instant().minusSeconds(3600),
            CLOCK.instant().minusSeconds(1));
    final RiskRoutingPolicyReadinessHealthIndicator indicator =
        new RiskRoutingPolicyReadinessHealthIndicator(new StubRepository(policy), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails())
        .containsEntry("reason", "STALE_OR_NOT_APPLICABLE_ROUTING_POLICY");
  }

  private RoutingPolicyProjection policy(int partitionCount, Instant from, Instant until) {
    return new RoutingPolicyProjection(
        new RoutingPolicyProjectionIdentity(
            UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01"),
            UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001"),
            LocalDate.of(2026, 7, 27)),
        new RoutingPolicyProjectionInterval(from, until),
        new RoutingPolicyPartitionTopology(partitionCount),
        List.of(new RoutingPolicyAssignment(new RoutingInstrument("2330", "XTAI"), 7)));
  }

  private static final class StubRepository implements RoutingPolicyProjectionRepository {
    private final Optional<RoutingPolicyProjection> policy;

    private StubRepository() {
      this.policy = Optional.empty();
    }

    private StubRepository(RoutingPolicyProjection policy) {
      this.policy = Optional.of(policy);
    }

    @Override
    public Optional<RoutingPolicyProjection> findById(UUID routingPolicyId) {
      return policy.filter(
          candidate -> candidate.identity().routingPolicyId().equals(routingPolicyId));
    }

    @Override
    public Optional<RoutingPolicyProjection> findApplicable(LocalDate tradingDay, Instant at) {
      return policy.filter(
          candidate ->
              candidate.identity().tradingDay().equals(tradingDay)
                  && candidate.appliesAt(at));
    }

    @Override
    public Optional<RoutingPolicyProjection> findLatestActive() {
      return policy;
    }

    @Override
    public void insertStaged(RoutingPolicyProjection projection, Instant receivedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void activate(UUID routingPolicyId) {
      throw new UnsupportedOperationException();
    }
  }
}
