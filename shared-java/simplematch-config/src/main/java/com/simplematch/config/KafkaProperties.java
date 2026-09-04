package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently bindable Kafka connectivity, topic, and partition capability.
 *
 * @param brokers comma-separated Kafka bootstrap servers
 * @param topics canonical topic names
 * @param partitions desired partition counts for ordered event streams
   */
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

  /**
   * Defines canonical Kafka topic names used by SimpleMatch event flows.
   *
   * @param matchingCommands topic carrying artifact-routed Matching commands
   * @param matchingEvents topic carrying deterministic Matching events
   * @param marketdataEvents topic carrying market-data events
   * @param auditEvents topic carrying audit events
   */
  public record TopicsProperties(
      String matchingCommands,
      String matchingEvents,
      String marketdataEvents,
      String auditEvents) {
    /** Normalizes absent topic names to repository-wide defaults. */
    public TopicsProperties {
      matchingCommands = PlatformPropertyDefaults.string(matchingCommands, "matching.commands");
      matchingEvents = PlatformPropertyDefaults.string(matchingEvents, "matching.events");
      marketdataEvents = PlatformPropertyDefaults.string(marketdataEvents, "marketdata.events");
      auditEvents = PlatformPropertyDefaults.string(auditEvents, "audit.events");
    }

    static TopicsProperties defaults() {
      return new TopicsProperties(null, null, null, null);
    }
  }

  /**
   * Defines desired Kafka partition counts for ordered event streams.
   *
   * @param matchingCommands partition count for the final Matching command journal
   * @param matchingEvents partition count for the final Matching event journal
  */
  public record PartitionsProperties(
      Integer matchingCommands,
      Integer matchingEvents) {
    /** Normalizes absent partition counts to repository-wide defaults. */
    public PartitionsProperties {
      matchingCommands = PlatformPropertyDefaults.integerOrDefault(matchingCommands, 15);
      matchingEvents = PlatformPropertyDefaults.integerOrDefault(matchingEvents, 15);
    }

    static PartitionsProperties defaults() {
      return new PartitionsProperties(null, null);
    }
  }
}
