package com.simplematch.riskservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Runtime settings for the Risk-owned CDC delivery observer. */
@ConfigurationProperties("simplematch.risk-service.cdc-delivery")
public record CdcDeliveryProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("false") boolean fixtureRecordsAllowed,
    @DefaultValue("risk-cdc-delivery") String consumerGroup,
    @DefaultValue("5s") Duration refreshInterval,
    @DefaultValue("5s") Duration queryTimeout) {

  private static final Duration MINIMUM_QUERY_TIMEOUT = Duration.ofMillis(1);
  private static final Duration MAXIMUM_QUERY_TIMEOUT = Duration.ofSeconds(10);

  /** Validates the bounded observer settings. */
  public CdcDeliveryProperties {
    if (consumerGroup == null || consumerGroup.isBlank()) {
      throw new IllegalArgumentException("consumerGroup must not be blank");
    }
    requirePositive(refreshInterval, "refreshInterval");
    requireBoundedQueryTimeout(queryTimeout);
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireBoundedQueryTimeout(Duration value) {
    requirePositive(value, "queryTimeout");
    if (value.compareTo(MINIMUM_QUERY_TIMEOUT) < 0) {
      throw new IllegalArgumentException("queryTimeout must be at least 1 millisecond");
    }
    if (value.compareTo(MAXIMUM_QUERY_TIMEOUT) > 0) {
      throw new IllegalArgumentException("queryTimeout must not exceed 10 seconds");
    }
  }
}
