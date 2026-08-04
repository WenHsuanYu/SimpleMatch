package com.simplematch.marketdatapublisher.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.marketdatapublisher.routing.RoutingAssignment;
import com.simplematch.marketdatapublisher.routing.RoutingPolicy;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyIdentity;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyInterval;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyRepository;
import com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity;
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

class RoutingPolicyReadinessHealthIndicatorTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC);

  @DisplayName("readiness fails closed when no routing policy is active")
  @Test
  void isOutOfServiceWhenPolicyIsMissing() {
    final RoutingPolicyReadinessHealthIndicator indicator =
        new RoutingPolicyReadinessHealthIndicator(new StubRepository(), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails()).containsEntry("reason", "MISSING_ROUTING_POLICY");
  }

  @DisplayName("readiness is up for a complete current policy with matching topology")
  @Test
  void isUpForApplicablePolicy() {
    final RoutingPolicy policy = policy(16, CLOCK.instant(), CLOCK.instant().plusSeconds(3600));
    final RoutingPolicyReadinessHealthIndicator indicator =
        new RoutingPolicyReadinessHealthIndicator(new StubRepository(policy), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(indicator.health().getDetails())
        .containsEntry("policyId", policy.identity().routingPolicyId().toString());
  }

  @DisplayName("readiness fails closed for a policy incompatible with Kafka topology")
  @Test
  void isOutOfServiceForPartitionTopologyMismatch() {
    final RoutingPolicy policy = policy(15, CLOCK.instant(), CLOCK.instant().plusSeconds(3600));
    final RoutingPolicyReadinessHealthIndicator indicator =
        new RoutingPolicyReadinessHealthIndicator(new StubRepository(policy), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails())
        .containsEntry("reason", "ROUTING_PARTITION_TOPOLOGY_MISMATCH");
  }

  @DisplayName("readiness fails closed after the current policy interval expires")
  @Test
  void isOutOfServiceForExpiredPolicy() {
    final RoutingPolicy policy =
        policy(
            16,
            CLOCK.instant().minusSeconds(3600),
            CLOCK.instant().minusSeconds(1));
    final RoutingPolicyReadinessHealthIndicator indicator =
        new RoutingPolicyReadinessHealthIndicator(new StubRepository(policy), CLOCK, 16);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(indicator.health().getDetails())
        .containsEntry("reason", "STALE_OR_NOT_YET_APPLICABLE_ROUTING_POLICY");
  }

  private RoutingPolicy policy(int partitionCount, Instant from, Instant until) {
    return new RoutingPolicy(
        new RoutingPolicyIdentity(
            UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01"),
            UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001"),
            LocalDate.of(2026, 7, 27)),
        new RoutingPolicyInterval(from, until),
        partitionCount,
        List.of(new RoutingAssignment(new InstrumentIdentity("2330", "XTAI"), 7)));
  }

  private static final class StubRepository implements RoutingPolicyRepository {
    private final Optional<RoutingPolicy> policy;

    private StubRepository() {
      this.policy = Optional.empty();
    }

    private StubRepository(RoutingPolicy policy) {
      this.policy = Optional.of(policy);
    }

    @Override
    public Optional<RoutingPolicy> findById(UUID routingPolicyId) {
      return policy.filter(
          candidate -> candidate.identity().routingPolicyId().equals(routingPolicyId));
    }

    @Override
    public Optional<RoutingPolicy> findApplicable(LocalDate tradingDay, Instant at) {
      return policy.filter(
          candidate ->
              candidate.identity().tradingDay().equals(tradingDay)
                  && candidate.appliesAt(at));
    }

    @Override
    public Optional<RoutingPolicy> findLatestForTradingDay(LocalDate tradingDay) {
      return policy.filter(candidate -> candidate.identity().tradingDay().equals(tradingDay));
    }

    @Override
    public Optional<RoutingPolicy> findLatestActive() {
      return policy;
    }

    @Override
    public void lockSourceSnapshot(UUID sourceMarketSnapshotId, LocalDate tradingDay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RoutingPolicy> findAllForTradingDayForUpdate(LocalDate tradingDay) {
      return policy.filter(candidate -> candidate.identity().tradingDay().equals(tradingDay))
          .stream()
          .toList();
    }

    @Override
    public void insert(RoutingPolicy policy, Instant publishedAt) {
      throw new UnsupportedOperationException();
    }
  }
}
