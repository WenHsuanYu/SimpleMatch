package com.simplematch.queryservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime ownership and delivery settings for the rebuildable query projections. */
@ConfigurationProperties("simplematch.query-service")
public record QueryServiceProperties(
    MatchingEvents matchingEvents,
    AccountLifecycle accountLifecycle,
    Redis redis,
    MarketReference marketReference) {
  /** Applies safe local defaults while keeping each source independently configurable. */
  public QueryServiceProperties {
    matchingEvents = matchingEvents == null ? MatchingEvents.defaults() : matchingEvents;
    accountLifecycle = accountLifecycle == null ? AccountLifecycle.defaults() : accountLifecycle;
    redis = redis == null ? Redis.defaults() : redis;
    marketReference = marketReference == null ? MarketReference.defaults() : marketReference;
  }

  /** Defines the final Matching Event consumer group. */
  public record MatchingEvents(boolean enabled, String topic, String consumerGroup) {
    /** Validates the Matching Event source identity. */
    public MatchingEvents {
      requireText(topic, "query Matching Event topic");
      requireText(consumerGroup, "query Matching Event consumer group");
    }

    private static MatchingEvents defaults() {
      return new MatchingEvents(false, "matching.events", "query-service-matching-events");
    }
  }

  /** Defines the Account lifecycle fact consumer group. */
  public record AccountLifecycle(boolean enabled, String topic, String consumerGroup) {
    /** Validates the Account lifecycle source identity. */
    public AccountLifecycle {
      requireText(topic, "query Account lifecycle topic");
      requireText(consumerGroup, "query Account lifecycle consumer group");
    }

    private static AccountLifecycle defaults() {
      return new AccountLifecycle(false, "account.lifecycle", "query-service-account-lifecycle");
    }
  }

  /** Defines optional Redis read-through cache ownership. */
  public record Redis(boolean enabled, String keyPrefix) {
    /** Validates the cache namespace. */
    public Redis {
      requireText(keyPrefix, "query Redis key prefix");
    }

    private static Redis defaults() {
      return new Redis(false, "query:v1");
    }
  }

  /** Defines the mounted final artifact identity used to populate the market-reference model. */
  public record MarketReference(
      String artifactLocation,
      String checksumLocation,
      String tradingDay,
      boolean installOnStartup) {
    /** Validates artifact paths; the day may be supplied by the deployment environment. */
    public MarketReference {
      requireText(artifactLocation, "query market-reference artifact location");
      requireText(checksumLocation, "query market-reference checksum location");
      tradingDay = tradingDay == null ? "" : tradingDay;
    }

    private static MarketReference defaults() {
      return new MarketReference(
          "/etc/simplematch/market-reference/market_reference.json",
          "/etc/simplematch/market-reference/market_reference.sha256",
          "",
          false);
    }
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }
}
