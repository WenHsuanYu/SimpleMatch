package com.simplematch.marketdataprojection.config;

import com.simplematch.config.RedisProperties;
import com.simplematch.config.delivery.DeadLetterStore;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCache;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheRefresher;
import com.simplematch.marketdataprojection.cache.RedisMarketDataSnapshotCache;
import com.simplematch.marketdataprojection.kafka.JdbcMarketDataProjectionDeadLetterStore;
import com.simplematch.marketdataprojection.kafka.KafkaMarketDataEventPublisher;
import com.simplematch.marketdataprojection.kafka.MarketDataEventPublisher;
import com.simplematch.marketdataprojection.kafka.MarketDataOutboxDispatcher;
import com.simplematch.marketdataprojection.kafka.MarketDataProjectionConsumer;
import com.simplematch.marketdataprojection.kafka.MarketDataProjectionRetryScheduler;
import com.simplematch.marketdataprojection.kafka.ScheduledMarketDataProjectionRetryScheduler;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionHandler;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires independent non-critical consumer, output, and Redis cache adapters. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(MarketDataProjectionProperties.class)
public class MarketDataProjectionRuntimeConfiguration {
  /** Persists durable evidence when the bounded non-critical retry budget is exhausted. */
  @Bean
  DeadLetterStore marketDataProjectionDeadLetterStore(
      JdbcTemplate marketDataProjectionJdbcTemplate) {
    return new JdbcMarketDataProjectionDeadLetterStore(marketDataProjectionJdbcTemplate);
  }

  /** Creates the isolated retry/DLQ policy that never pauses Persistence, Account, or Gateway. */
  @Bean
  NonCriticalDeliveryController marketDataProjectionDeliveryController(
      MarketDataProjectionProperties properties,
      Clock marketDataProjectionClock,
      DeadLetterStore marketDataProjectionDeadLetterStore,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    final MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        meterRegistry == null
            ? DeliveryMetrics.noop()
            : new MicrometerDeliveryMetrics(meterRegistry);
    return new NonCriticalDeliveryController(
        "market-data-projection",
        properties.matchingEvents().maximumAttempts(),
        properties.matchingEvents().retryDelay(),
        marketDataProjectionClock,
        marketDataProjectionDeadLetterStore,
        metrics);
  }

  /** Creates a daemon scheduler that retries only the projection's own rebuildable work. */
  @Bean(destroyMethod = "close")
  MarketDataProjectionRetryScheduler marketDataProjectionRetryScheduler() {
    return new ScheduledMarketDataProjectionRetryScheduler();
  }

  /** Creates the independent final Matching Event consumer group boundary. */
  @Bean
  MarketDataProjectionConsumer marketDataProjectionConsumer(
      MarketDataProjectionHandler marketDataProjectionHandler,
      NonCriticalDeliveryController marketDataProjectionDeliveryController,
      MarketDataProjectionRetryScheduler marketDataProjectionRetryScheduler) {
    return new MarketDataProjectionConsumer(
        marketDataProjectionHandler,
        marketDataProjectionDeliveryController,
        marketDataProjectionRetryScheduler);
  }

  /** Creates the at-least-once Kafka publication adapter only when runtime output is enabled. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.marketdata-events.enabled",
      havingValue = "true")
  MarketDataEventPublisher marketDataEventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate) {
    return new KafkaMarketDataEventPublisher(kafkaTemplate);
  }

  /** Creates the scheduled durable outbox dispatcher only when market-data output is enabled. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.marketdata-events.enabled",
      havingValue = "true")
  ScheduledMarketDataOutboxDispatcher scheduledMarketDataOutboxDispatcher(
      MarketDataProjectionStore marketDataProjectionStore,
      MarketDataEventPublisher marketDataEventPublisher,
      Clock marketDataProjectionClock,
      MarketDataProjectionProperties properties) {
    return new ScheduledMarketDataOutboxDispatcher(
        new MarketDataOutboxDispatcher(
            marketDataProjectionStore,
            marketDataEventPublisher,
            marketDataProjectionClock,
            properties.marketdataEvents().dispatchBatchSize()));
  }

  /** Creates a Redis connection factory only when the optional rebuildable cache is enabled. */
  @Bean(destroyMethod = "destroy")
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.redis.enabled",
      havingValue = "true")
  RedisConnectionFactory marketDataProjectionRedisConnectionFactory(RedisProperties properties) {
    final RedisEndpoint endpoint = RedisEndpoint.parse(properties.endpoints());
    return new LettuceConnectionFactory(
        new RedisStandaloneConfiguration(endpoint.host(), endpoint.port()));
  }

  /** Creates the byte-preserving Redis template used for complete Protobuf snapshots. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.redis.enabled",
      havingValue = "true")
  RedisTemplate<String, byte[]> marketDataProjectionRedisTemplate(
      RedisConnectionFactory marketDataProjectionRedisConnectionFactory) {
    final RedisTemplate<String, byte[]> template = new RedisTemplate<>();
    template.setConnectionFactory(marketDataProjectionRedisConnectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(RedisSerializer.byteArray());
    template.afterPropertiesSet();
    return template;
  }

  /** Creates the optional Redis snapshot adapter. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.redis.enabled",
      havingValue = "true")
  MarketDataSnapshotCache marketDataSnapshotCache(
      RedisTemplate<String, byte[]> marketDataProjectionRedisTemplate) {
    return new RedisMarketDataSnapshotCache(marketDataProjectionRedisTemplate);
  }

  /** Creates scheduled cache repair from durable snapshots only when Redis is configured. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.redis.enabled",
      havingValue = "true")
  ScheduledMarketDataSnapshotCacheRefresher scheduledMarketDataSnapshotCacheRefresher(
      MarketDataProjectionStore marketDataProjectionStore,
      MarketDataSnapshotCache marketDataSnapshotCache,
      MarketDataProjectionProperties properties) {
    return new ScheduledMarketDataSnapshotCacheRefresher(
        new MarketDataSnapshotCacheRefresher(
            marketDataProjectionStore,
            marketDataSnapshotCache,
            properties.redis().refreshBatchSize()));
  }

  /**
   * Calls the dispatcher from Spring scheduling without leaking scheduling mechanics into its
   * interface.
   */
  public static final class ScheduledMarketDataOutboxDispatcher {
    private final MarketDataOutboxDispatcher dispatcher;

    private ScheduledMarketDataOutboxDispatcher(MarketDataOutboxDispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    /** Runs one bounded output batch. */
    @Scheduled(
        fixedDelayString =
            "${simplematch.market-data-projection.marketdata-events.dispatch-interval:1s}")
    public void dispatch() {
      dispatcher.dispatchPending();
    }
  }

  /**
   * Calls cache repair from Spring scheduling without coupling the projection transaction to Redis.
   */
  public static final class ScheduledMarketDataSnapshotCacheRefresher {
    private final MarketDataSnapshotCacheRefresher refresher;

    private ScheduledMarketDataSnapshotCacheRefresher(MarketDataSnapshotCacheRefresher refresher) {
      this.refresher = refresher;
    }

    /** Runs one bounded cache repair batch. */
    @Scheduled(fixedDelayString = "${simplematch.market-data-projection.redis.refresh-interval:5s}")
    public void refresh() {
      refresher.refreshPending();
    }
  }

  private record RedisEndpoint(String host, int port) {
    private static RedisEndpoint parse(String endpoints) {
      final String endpoint = endpoints.split(",", 2)[0].trim();
      final int separator = endpoint.lastIndexOf(':');
      if (separator <= 0 || separator == endpoint.length() - 1) {
        throw new IllegalArgumentException("Redis endpoint must use host:port syntax");
      }
      final int port;
      try {
        port = Integer.parseInt(endpoint.substring(separator + 1));
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("Redis endpoint port must be numeric", invalid);
      }
      if (port < 1 || port > 65_535) {
        throw new IllegalArgumentException("Redis endpoint port must be between 1 and 65535");
      }
      return new RedisEndpoint(endpoint.substring(0, separator), port);
    }
  }
}
