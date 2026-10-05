package com.simplematch.quickfixgateway.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds bounded internal endpoints and Kafka groups used by live admission observation. */
@ConfigurationProperties("simplematch.quickfix-gateway.live-observation")
public record GatewayLiveObservationProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("PT1S") Duration requestTimeout,
    @DefaultValue("PT3S") Duration collectionTimeout,
    @DefaultValue("1000") long intervalMillis,
    @DefaultValue Endpoints endpoints,
    @DefaultValue ConsumerGroups consumerGroups) {
  /** Validates positive, ordered request and whole-collection time limits. */
  public GatewayLiveObservationProperties {
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(collectionTimeout, "collectionTimeout");
    Objects.requireNonNull(endpoints, "endpoints");
    Objects.requireNonNull(consumerGroups, "consumerGroups");
    if (requestTimeout.isZero()
        || requestTimeout.isNegative()
        || collectionTimeout.isZero()
        || collectionTimeout.isNegative()
        || collectionTimeout.compareTo(requestTimeout) <= 0
        || intervalMillis <= 0) {
      throw new IllegalArgumentException("live observation timing must be positive");
    }
  }

  /** Internal HTTP sources for Risk, Matching, and critical consumer facts. */
  public record Endpoints(
      @DefaultValue("http://risk-service:8080/internal/operational-status") String risk,
      @DefaultValue("http://matching-%d.matching-headless:8081/runtime-metrics.json")
          String matchingTemplate,
      @DefaultValue("http://account-service:8080/internal/critical-consumer-status")
          String account,
      @DefaultValue("http://persistence:8080/internal/critical-consumer-status")
          String persistence) {
    /** Requires every endpoint and the fixed-partition Matching URL template. */
    public Endpoints {
      risk = required(risk, "risk");
      matchingTemplate = required(matchingTemplate, "matchingTemplate");
      account = required(account, "account");
      persistence = required(persistence, "persistence");
      if (!matchingTemplate.contains("%d")) {
        throw new IllegalArgumentException("matchingTemplate must contain a partition placeholder");
      }
    }
  }

  /** Kafka group identities whose committed progress protects trading admission. */
  public record ConsumerGroups(
      @DefaultValue("persistence-matching-events") String persistence,
      @DefaultValue("account-final-matching-events") String account,
      @DefaultValue("quickfix-final-matching-events") String quickfix) {
    /** Requires all three stable consumer group identities. */
    public ConsumerGroups {
      persistence = required(persistence, "persistence");
      account = required(account, "account");
      quickfix = required(quickfix, "quickfix");
    }
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
