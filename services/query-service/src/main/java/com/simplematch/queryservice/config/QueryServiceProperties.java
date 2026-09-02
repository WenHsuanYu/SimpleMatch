package com.simplematch.queryservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Runtime ownership and delivery settings for the rebuildable query projections. */
@ConfigurationProperties("simplematch.query-service")
public record QueryServiceProperties(
    MatchingEvents matchingEvents,
    AccountLifecycle accountLifecycle,
    Redis redis,
    MarketReference marketReference,
    Rebuild rebuild) {
  /** Applies safe local defaults while keeping each source independently configurable. */
  public QueryServiceProperties {
    matchingEvents = matchingEvents == null ? MatchingEvents.defaults() : matchingEvents;
    accountLifecycle = accountLifecycle == null ? AccountLifecycle.defaults() : accountLifecycle;
    redis = redis == null ? Redis.defaults() : redis;
    marketReference = marketReference == null ? MarketReference.defaults() : marketReference;
    rebuild = rebuild == null ? Rebuild.defaults() : rebuild;
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
  public record Redis(
      boolean enabled, String keyPrefix, Duration commandTimeout, Duration connectTimeout) {
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration MAX_FAILURE_TIMEOUT = Duration.ofSeconds(10);

    /** Keeps the original two-value construction seam while applying bounded client defaults. */
    public Redis(boolean enabled, String keyPrefix) {
      this(enabled, keyPrefix, null, null);
    }

    /** Validates the cache namespace and bounds each Redis failure wait. */
    @ConstructorBinding
    public Redis {
      requireText(keyPrefix, "query Redis key prefix");
      commandTimeout =
          positiveOrDefault(commandTimeout, DEFAULT_COMMAND_TIMEOUT, "commandTimeout");
      connectTimeout =
          positiveOrDefault(connectTimeout, DEFAULT_CONNECT_TIMEOUT, "connectTimeout");
    }

    private static Redis defaults() {
      return new Redis(false, "query:v1", DEFAULT_COMMAND_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
    }

    private static Duration positiveOrDefault(
        Duration value, Duration defaultValue, String name) {
      if (value == null) {
        return defaultValue;
      }
      if (value.isNegative() || value.isZero()) {
        throw new IllegalArgumentException("query Redis " + name + " must be positive");
      }
      if (value.compareTo(MAX_FAILURE_TIMEOUT) > 0) {
        throw new IllegalArgumentException("query Redis " + name + " must not exceed 10 seconds");
      }
      if (value.toMillis() <= 0) {
        throw new IllegalArgumentException("query Redis " + name + " must be at least 1ms");
      }
      return value;
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

  /** Defines the separately authenticated operator seam for a projection replay reset. */
  public record Rebuild(boolean httpEnabled, String operatorToken) {
    /** Rejects an enabled reset endpoint without an externally supplied token. */
    public Rebuild {
      if (httpEnabled && (operatorToken == null || operatorToken.isBlank())) {
        throw new IllegalArgumentException(
            "query rebuild operatorToken is required when rebuild HTTP is enabled");
      }
      operatorToken = operatorToken == null ? "" : operatorToken;
    }

    private static Rebuild defaults() {
      return new Rebuild(false, "");
    }
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }
}
