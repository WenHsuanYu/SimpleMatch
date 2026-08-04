package com.simplematch.config;

/** Validates the cross-service platform settings that do not depend on Spring environment state. */
final class PlatformSettingsValidator {
  private PlatformSettingsValidator() {}

  static void validate(PlatformProperties properties) {
    validateKafkaPartitions(properties.kafka().partitions());
    validatePrometheusPort(properties.observability().prometheus().port());
    validateMarketDefaults(properties.market());
  }

  static void validate(KafkaProperties properties) {
    validateKafkaPartitions(
        properties.partitions().ordersCommands(),
        properties.partitions().ordersValidated(),
        properties.partitions().matchingExecutions());
  }

  static void validate(ObservabilityProperties properties) {
    validatePrometheusPort(properties.prometheus().port());
  }

  static void validate(MarketProperties properties) {
    validateMarketDefaults(properties.currency(), properties.timeZone());
  }

  private static void validateKafkaPartitions(PlatformProperties.PartitionsProperties partitions) {
    validateKafkaPartitions(
        partitions.ordersCommands(), partitions.ordersValidated(), partitions.matchingExecutions());
  }

  private static void validateKafkaPartitions(
      int ordersCommands, int ordersValidated, int matchingExecutions) {
    if (ordersCommands <= 0 || ordersValidated <= 0 || matchingExecutions <= 0) {
      throw new IllegalStateException("Kafka partition counts must be positive.");
    }
  }

  private static void validatePrometheusPort(int port) {
    if (port < 1 || port > 65_535) {
      throw new IllegalStateException("Prometheus port must be between 1 and 65535.");
    }
  }

  private static void validateMarketDefaults(PlatformProperties.MarketProperties market) {
    validateMarketDefaults(market.currency(), market.timeZone());
  }

  private static void validateMarketDefaults(String currency, String timeZone) {
    if (!"TWD".equals(currency)) {
      throw new IllegalStateException("Phase-one market currency must be TWD.");
    }
    if (!"Asia/Taipei".equals(timeZone)) {
      throw new IllegalStateException("Phase-one market time zone must be Asia/Taipei.");
    }
  }
}
