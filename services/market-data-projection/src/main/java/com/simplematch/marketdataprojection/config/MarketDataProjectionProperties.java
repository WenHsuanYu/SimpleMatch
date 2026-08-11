package com.simplematch.marketdataprojection.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed runtime configuration for rebuildable final-event market-data projection work. */
@ConfigurationProperties("simplematch.market-data-projection")
public record MarketDataProjectionProperties(
    MatchingEvents matchingEvents, MarketdataEvents marketdataEvents, Redis redis) {
  /** Normalizes independently deployable consumer, output, and cache configuration. */
  public MarketDataProjectionProperties {
    matchingEvents = matchingEvents == null ? MatchingEvents.defaults() : matchingEvents;
    marketdataEvents = marketdataEvents == null ? MarketdataEvents.defaults() : marketdataEvents;
    redis = redis == null ? Redis.defaults() : redis;
  }

  /** Defines non-critical Matching Event consumption and delayed retry policy. */
  public record MatchingEvents(
      boolean enabled, String topic, int maximumAttempts, Duration retryDelay) {
    /** Validates a bounded delayed-retry configuration. */
    public MatchingEvents {
      if (topic == null || topic.isBlank() || maximumAttempts <= 0) {
        throw new IllegalArgumentException("market-data Matching Event configuration is invalid");
      }
      if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
        throw new IllegalArgumentException("market-data retry delay must be positive");
      }
    }

    private static MatchingEvents defaults() {
      return new MatchingEvents(false, "matching.events", 3, Duration.ofSeconds(5));
    }
  }

  /** Defines the complete-snapshot output stream and bounded background dispatch cadence. */
  public record MarketdataEvents(
      boolean enabled, String topic, int dispatchBatchSize, Duration dispatchInterval) {
    /** Validates output settings that remain separate from Matching Event consumption. */
    public MarketdataEvents {
      if (topic == null || topic.isBlank() || dispatchBatchSize <= 0) {
        throw new IllegalArgumentException("market-data output configuration is invalid");
      }
      if (dispatchInterval == null || dispatchInterval.isNegative() || dispatchInterval.isZero()) {
        throw new IllegalArgumentException("market-data dispatch interval must be positive");
      }
    }

    private static MarketdataEvents defaults() {
      return new MarketdataEvents(false, "marketdata.events", 100, Duration.ofSeconds(1));
    }
  }

  /** Defines optional Redis materialization that may lag behind the durable projection. */
  public record Redis(boolean enabled, int refreshBatchSize, Duration refreshInterval) {
    /** Validates bounded asynchronous Redis repair settings. */
    public Redis {
      if (refreshBatchSize <= 0) {
        throw new IllegalArgumentException("market-data Redis refresh batch size must be positive");
      }
      if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()) {
        throw new IllegalArgumentException("market-data Redis refresh interval must be positive");
      }
    }

    private static Redis defaults() {
      return new Redis(false, 100, Duration.ofSeconds(5));
    }
  }
}
