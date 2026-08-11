package com.simplematch.quickfixgateway.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds the single Gateway's Phase 1 operational admission safety policy. */
@ConfigurationProperties("simplematch.quickfix-gateway.operations")
public record QuickFixGatewayOperationsProperties(
    @DefaultValue("3") int requiredConsecutiveOpenEligibleChecks,
    @DefaultValue("PT5S") Duration staleStatusAfter,
    @DefaultValue("PT30S") Duration warningOldestEventAfter,
    @DefaultValue("PT2M") Duration pauseOldestEventAfter,
    @DefaultValue("Asia/Taipei") String sessionZone,
    @DefaultValue("13:30") String sessionCloseTime,
    @DefaultValue("true") boolean automaticCloseEnabled,
    @DefaultValue("1000") long monitorIntervalMillis,
    @DefaultValue("true") boolean monitorEnabled) {
  /** Validates configuration before the single Gateway can accept operational commands. */
  public QuickFixGatewayOperationsProperties {
    if (requiredConsecutiveOpenEligibleChecks <= 0) {
      throw new IllegalArgumentException("requiredConsecutiveOpenEligibleChecks must be positive");
    }
    if (staleStatusAfter == null
        || warningOldestEventAfter == null
        || pauseOldestEventAfter == null
        || staleStatusAfter.isNegative()
        || warningOldestEventAfter.isNegative()
        || pauseOldestEventAfter.isNegative()) {
      throw new IllegalArgumentException("Gateway operational durations must be non-negative");
    }
    if (pauseOldestEventAfter.compareTo(warningOldestEventAfter) < 0) {
      throw new IllegalArgumentException(
          "pauseOldestEventAfter must not precede warningOldestEventAfter");
    }
    if (monitorIntervalMillis <= 0) {
      throw new IllegalArgumentException("monitorIntervalMillis must be positive");
    }
    sessionZone = requireText(sessionZone, "sessionZone");
    sessionCloseTime = requireText(sessionCloseTime, "sessionCloseTime");
    ZoneId.of(sessionZone);
    LocalTime.parse(sessionCloseTime);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
