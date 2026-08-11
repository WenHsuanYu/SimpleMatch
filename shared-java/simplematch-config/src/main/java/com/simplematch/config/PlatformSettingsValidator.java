package com.simplematch.config;

/** Validates the cross-service platform settings that do not depend on Spring environment state. */
final class PlatformSettingsValidator {
  private PlatformSettingsValidator() {}

  static void validate(KafkaProperties properties) {
    validateKafkaPartitions(properties.partitions());
  }

  static void validate(ObservabilityProperties properties) {
    validatePrometheusPort(properties.prometheus());
  }

  static void validate(MarketProperties properties) {
    validateMarketDefaults(properties);
  }

  private static void validateKafkaPartitions(KafkaProperties.PartitionsProperties partitions) {
    if (partitions.ordersValidated() <= 0
        || partitions.matchingExecutions() <= 0
        || partitions.matchingCommands() <= 0
        || partitions.matchingEvents() <= 0) {
      throw new IllegalStateException("Kafka partition counts must be positive.");
    }
  }

  private static void validatePrometheusPort(
      ObservabilityProperties.PrometheusProperties prometheus) {
    final int port = prometheus.port();
    if (port < 1 || port > 65_535) {
      throw new IllegalStateException("Prometheus port must be between 1 and 65535.");
    }
  }

  private static void validateMarketDefaults(MarketProperties properties) {
    if (!"TWD".equals(properties.currency())) {
      throw new IllegalStateException("Phase-one market currency must be TWD.");
    }
    if (!"Asia/Taipei".equals(properties.timeZone())) {
      throw new IllegalStateException("Phase-one market time zone must be Asia/Taipei.");
    }
  }
}
