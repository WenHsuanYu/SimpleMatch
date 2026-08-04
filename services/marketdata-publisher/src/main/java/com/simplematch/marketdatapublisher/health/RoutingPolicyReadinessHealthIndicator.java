package com.simplematch.marketdatapublisher.health;

import com.simplematch.marketdatapublisher.routing.RoutingPolicy;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyRepository;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Fails closed until a complete, current, topology-compatible routing policy is applicable. */
public final class RoutingPolicyReadinessHealthIndicator implements HealthIndicator {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final RoutingPolicyRepository policies;
  private final Clock clock;
  private final int expectedPartitionCount;

  /** Creates readiness checks against the current Taiwan day and configured Kafka topology. */
  public RoutingPolicyReadinessHealthIndicator(
      RoutingPolicyRepository policies, Clock clock, int expectedPartitionCount) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (expectedPartitionCount <= 0) {
      throw new IllegalArgumentException("expected partition count must be positive");
    }
    this.expectedPartitionCount = expectedPartitionCount;
  }

  /**
   * Returns out-of-service for missing, stale, incomplete, invalid, or incompatible policy data.
   */
  @Override
  public Health health() {
    final var now = clock.instant();
    final LocalDate expectedTradingDay = now.atZone(TAIPEI).toLocalDate();
    try {
      final Optional<RoutingPolicy> applicable = policies.findApplicable(expectedTradingDay, now);
      if (applicable.isPresent()) {
        return healthFor(applicable.orElseThrow(), expectedTradingDay);
      }
      return unavailableFor(expectedTradingDay);
    } catch (RoutingPolicyValidationException exception) {
      return Health.outOfService()
          .withDetail("reason", "INVALID_ROUTING_POLICY")
          .withDetail("expectedTradingDay", expectedTradingDay.toString())
          .withDetail("message", exception.getMessage())
          .build();
    }
  }

  private Health healthFor(RoutingPolicy policy, LocalDate expectedTradingDay) {
    if (policy.assignments().isEmpty()) {
      return Health.outOfService()
          .withDetail("reason", "INCOMPLETE_ROUTING_POLICY")
          .withDetail("policyId", policy.identity().routingPolicyId().toString())
          .build();
    }
    if (policy.ordersValidatedPartitionCount() != expectedPartitionCount) {
      return Health.outOfService()
          .withDetail("reason", "ROUTING_PARTITION_TOPOLOGY_MISMATCH")
          .withDetail("policyPartitionCount", policy.ordersValidatedPartitionCount())
          .withDetail("expectedPartitionCount", expectedPartitionCount)
          .build();
    }
    return Health.up()
        .withDetail("policyId", policy.identity().routingPolicyId().toString())
        .withDetail("tradingDay", expectedTradingDay.toString())
        .withDetail("partitionCount", policy.ordersValidatedPartitionCount())
        .build();
  }

  private Health unavailableFor(LocalDate expectedTradingDay) {
    final Optional<RoutingPolicy> sameDay = policies.findLatestForTradingDay(expectedTradingDay);
    if (sameDay.isPresent()) {
      return Health.outOfService()
          .withDetail("reason", "STALE_OR_NOT_YET_APPLICABLE_ROUTING_POLICY")
          .withDetail("expectedTradingDay", expectedTradingDay.toString())
          .withDetail("policyId", sameDay.orElseThrow().identity().routingPolicyId().toString())
          .build();
    }
    final Optional<RoutingPolicy> latest = policies.findLatestActive();
    if (latest.isPresent()) {
      return Health.outOfService()
          .withDetail("reason", "STALE_ROUTING_POLICY")
          .withDetail("expectedTradingDay", expectedTradingDay.toString())
          .withDetail("policyTradingDay", latest.orElseThrow().identity().tradingDay().toString())
          .build();
    }
    return Health.outOfService()
        .withDetail("reason", "MISSING_ROUTING_POLICY")
        .withDetail("expectedTradingDay", expectedTradingDay.toString())
        .build();
  }
}
