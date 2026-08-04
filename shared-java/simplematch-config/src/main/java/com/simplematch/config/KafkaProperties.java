package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independently bindable Kafka connectivity, topic, and partition capability. */
@ConfigurationProperties("simplematch.kafka")
public record KafkaProperties(
    String brokers, TopicsProperties topics, PartitionsProperties partitions) {
  /** Normalizes absent Kafka groups and broker settings to local-development defaults. */
  public KafkaProperties {
    brokers = PlatformPropertyDefaults.string(brokers, "localhost:9092");
    topics = topics == null ? TopicsProperties.defaults() : topics;
    partitions = partitions == null ? PartitionsProperties.defaults() : partitions;
  }

  static KafkaProperties defaults() {
    return new KafkaProperties(null, null, null);
  }

  /** Defines canonical Kafka topic names used by SimpleMatch event flows. */
  public record TopicsProperties(
      String ordersCommands,
      String ordersValidated,
      String matchingExecutions,
      String marketdataEvents,
      String auditEvents) {
    /** Normalizes absent topic names to repository-wide defaults. */
    public TopicsProperties {
      ordersCommands = PlatformPropertyDefaults.string(ordersCommands, "orders.commands");
      ordersValidated = PlatformPropertyDefaults.string(ordersValidated, "orders.validated");
      matchingExecutions =
          PlatformPropertyDefaults.string(matchingExecutions, "matching.executions");
      marketdataEvents = PlatformPropertyDefaults.string(marketdataEvents, "marketdata.events");
      auditEvents = PlatformPropertyDefaults.string(auditEvents, "audit.events");
    }

    static TopicsProperties defaults() {
      return new TopicsProperties(null, null, null, null, null);
    }
  }

  /** Defines desired Kafka partition counts for ordered event streams. */
  public record PartitionsProperties(
      Integer ordersCommands, Integer ordersValidated, Integer matchingExecutions) {
    /** Normalizes absent partition counts to repository-wide defaults. */
    public PartitionsProperties {
      ordersCommands = PlatformPropertyDefaults.positive(ordersCommands, 15);
      ordersValidated = PlatformPropertyDefaults.positive(ordersValidated, 15);
      matchingExecutions = PlatformPropertyDefaults.positive(matchingExecutions, 15);
    }

    static PartitionsProperties defaults() {
      return new PartitionsProperties(null, null, null);
    }
  }
}
