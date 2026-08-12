package com.simplematch.marketdataprojection.config;

import com.simplematch.config.RedisProperties;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCache;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheRefresher;
import com.simplematch.marketdataprojection.cache.RedisMarketDataSnapshotCache;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires the optional Redis snapshot cache and its durable repair scheduler. */
@Configuration(proxyBeanMethods = false)
public class MarketDataProjectionRedisConfiguration {
  /** Creates a Redis connection factory only when the rebuildable cache is enabled. */
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

  /** Creates scheduled cache repair from durable snapshots when Redis is configured. */
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

  /** Calls cache repair without coupling the projection transaction to Redis. */
  public static final class ScheduledMarketDataSnapshotCacheRefresher {
    private final MarketDataSnapshotCacheRefresher refresher;

    private ScheduledMarketDataSnapshotCacheRefresher(MarketDataSnapshotCacheRefresher refresher) {
      this.refresher = refresher;
    }

    /** Runs one bounded cache repair batch. */
    @Scheduled(
        fixedDelayString = "${simplematch.market-data-projection.redis.refresh-interval:5s}")
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
