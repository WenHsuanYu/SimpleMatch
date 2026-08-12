package com.simplematch.marketdatastreamer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the non-critical public market-data stream. */
@ConfigurationProperties("simplematch.marketdata-streamer")
public record MarketdataStreamerProperties(
    boolean enabled,
    String topic,
    String consumerGroup,
    int grpcPort,
    int subscriberQueueCapacity,
    int maximumSubscribers) {
  /** Applies safe local defaults and validates bounded subscriber resources. */
  public MarketdataStreamerProperties {
    topic = topic == null || topic.isBlank() ? "marketdata.events" : topic;
    consumerGroup =
        consumerGroup == null || consumerGroup.isBlank()
            ? "marketdata-streamer"
            : consumerGroup;
    grpcPort = grpcPort == 0 ? 50053 : grpcPort;
    if (grpcPort < 1 || grpcPort > 65_535) {
      throw new IllegalArgumentException("grpcPort must be between 1 and 65535");
    }
    subscriberQueueCapacity = subscriberQueueCapacity == 0 ? 256 : subscriberQueueCapacity;
    if (subscriberQueueCapacity < 1) {
      throw new IllegalArgumentException("subscriberQueueCapacity must be positive");
    }
    maximumSubscribers = maximumSubscribers == 0 ? 256 : maximumSubscribers;
    if (maximumSubscribers < 1) {
      throw new IllegalArgumentException("maximumSubscribers must be positive");
    }
  }
}
