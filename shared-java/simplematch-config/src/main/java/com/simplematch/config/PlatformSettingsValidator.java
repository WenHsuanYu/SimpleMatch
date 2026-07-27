package com.simplematch.config;

/** Validates the cross-service platform settings that do not depend on Spring environment state. */
final class PlatformSettingsValidator {
  private PlatformSettingsValidator() {}

  static void validate(PlatformProperties properties) {
    validateKafkaPartitions(properties.kafka().partitions());
    validatePrometheusPort(properties.observability().prometheus().port());
    validateMarketDefaults(properties.market());
  }

  private static void validateKafkaPartitions(PlatformProperties.PartitionsProperties partitions) {
    if (partitions.ordersCommands() <= 0
        || partitions.ordersValidated() <= 0
        || partitions.matchingExecutions() <= 0) {
      throw new IllegalStateException("Kafka partition counts must be positive.");
    }
  }

  private static void validatePrometheusPort(int port) {
    if (port < 1 || port > 65_535) {
      throw new IllegalStateException("Prometheus port must be between 1 and 65535.");
    }
  }

  private static void validateMarketDefaults(PlatformProperties.MarketProperties market) {
    if (!"TWD".equals(market.currency())) {
      throw new IllegalStateException("Phase-one market currency must be TWD.");
    }
    if (!"Asia/Taipei".equals(market.timeZone())) {
      throw new IllegalStateException("Phase-one market time zone must be Asia/Taipei.");
    }
  }
}
