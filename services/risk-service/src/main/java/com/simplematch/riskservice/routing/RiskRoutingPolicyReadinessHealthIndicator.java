package com.simplematch.riskservice.routing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Fails closed until Risk has a complete current local policy matching its Kafka topology. */
public final class RiskRoutingPolicyReadinessHealthIndicator implements HealthIndicator {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final RoutingPolicyProjectionRepository repository;
  private final Clock clock;
  private final int expectedPartitionCount;

  /** Creates readiness checks for the current Taiwan trading day and Kafka topology. */
  public RiskRoutingPolicyReadinessHealthIndicator(
      RoutingPolicyProjectionRepository repository, Clock clock, int expectedPartitionCount) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (expectedPartitionCount <= 0) {
      throw new IllegalArgumentException("expected partition count must be positive");
    }
    this.expectedPartitionCount = expectedPartitionCount;
  }

  /** Returns out-of-service when no valid applicable local projection exists. */
  @Override
  public Health health() {
    final var now = clock.instant();
    final LocalDate expectedTradingDay = now.atZone(TAIPEI).toLocalDate();
    try {
      final Optional<RoutingPolicyProjection> applicable =
          repository.findApplicable(expectedTradingDay, now);
      if (applicable.isPresent()) {
        final RoutingPolicyProjection policy = applicable.orElseThrow();
        if (policy.topology().partitionCount() != expectedPartitionCount) {
          return Health.outOfService()
              .withDetail("reason", "ROUTING_PARTITION_TOPOLOGY_MISMATCH")
              .withDetail("policyPartitionCount", policy.topology().partitionCount())
              .withDetail("expectedPartitionCount", expectedPartitionCount)
              .build();
        }
        if (policy.assignments().isEmpty()) {
          return Health.outOfService()
              .withDetail("reason", "INCOMPLETE_ROUTING_POLICY")
              .build();
        }
        return Health.up()
            .withDetail("policyId", policy.identity().routingPolicyId().toString())
            .withDetail("tradingDay", expectedTradingDay.toString())
            .build();
      }
      final Optional<RoutingPolicyProjection> latest = repository.findLatestActive();
      if (latest.isPresent()) {
        return Health.outOfService()
            .withDetail("reason", "STALE_OR_NOT_APPLICABLE_ROUTING_POLICY")
            .withDetail("expectedTradingDay", expectedTradingDay.toString())
            .withDetail("policyTradingDay", latest.orElseThrow().identity().tradingDay().toString())
            .build();
      }
      return Health.outOfService()
          .withDetail("reason", "MISSING_ROUTING_POLICY")
          .withDetail("expectedTradingDay", expectedTradingDay.toString())
          .build();
    } catch (RoutingPolicyProjectionValidationException exception) {
      return Health.outOfService()
          .withDetail("reason", "INVALID_ROUTING_POLICY")
          .withDetail("expectedTradingDay", expectedTradingDay.toString())
          .withDetail("message", exception.getMessage())
          .build();
    }
  }
}
