package com.simplematch.riskservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Runtime settings for the Risk-owned CDC delivery observer. */
@ConfigurationProperties("simplematch.risk-service.cdc-delivery")
public record CdcDeliveryProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("risk-cdc-delivery") String consumerGroup,
    @DefaultValue("5s") Duration refreshInterval,
    @DefaultValue("5s") Duration queryTimeout) {

  /** Validates the bounded observer settings. */
  public CdcDeliveryProperties {
    if (consumerGroup == null || consumerGroup.isBlank()) {
      throw new IllegalArgumentException("consumerGroup must not be blank");
    }
    requirePositive(refreshInterval, "refreshInterval");
    requirePositive(queryTimeout, "queryTimeout");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
