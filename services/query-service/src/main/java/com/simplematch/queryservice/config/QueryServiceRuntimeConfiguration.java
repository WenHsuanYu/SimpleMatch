package com.simplematch.queryservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.simplematch.queryservice.runtime.NoopQueryReadCache;
import com.simplematch.queryservice.runtime.QueryMarketReferenceArtifactLoader;
import com.simplematch.queryservice.runtime.QueryMarketReferenceInstallationService;
import com.simplematch.queryservice.runtime.QueryMarketReferenceStartupInstaller;
import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.runtime.RedisQueryReadCache;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Wires artifact installation and the optional Redis acceleration layer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryServiceProperties.class)
public class QueryServiceRuntimeConfiguration {
  /** Supplies a module-aware Jackson mapper for the shared artifact codec and cache adapter. */
  @Bean
  ObjectMapper queryServiceObjectMapper() {
    return new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  /** Creates the verified mounted-artifact loader used by the active reference model. */
  @Bean
  QueryMarketReferenceArtifactLoader queryMarketReferenceArtifactLoader(
      ResourceLoader resourceLoader, ObjectMapper objectMapper, QueryServiceProperties properties) {
    return new QueryMarketReferenceArtifactLoader(resourceLoader, objectMapper, properties);
  }

  /** Creates the explicit artifact installation operation. */
  @Bean
  QueryMarketReferenceInstallationService queryMarketReferenceInstallationService(
      QueryMarketReferenceArtifactLoader loader,
      QueryProjectionRebuildService rebuildService,
      Clock queryServiceClock) {
    return new QueryMarketReferenceInstallationService(loader, rebuildService, queryServiceClock);
  }

  /** Registers the opt-in startup cutover for production's mounted final artifact. */
  @Bean
  QueryMarketReferenceStartupInstaller queryMarketReferenceStartupInstaller(
      QueryMarketReferenceInstallationService installationService,
      QueryServiceProperties properties) {
    return new QueryMarketReferenceStartupInstaller(installationService, properties);
  }

  /** Uses an empty cache adapter when Redis is deliberately disabled. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.query-service.redis.enabled",
      havingValue = "false",
      matchIfMissing = true)
  QueryReadCache noopQueryReadCache() {
    return new NoopQueryReadCache();
  }

  /** Creates byte-preserving Redis values for versioned read responses. */
  @Bean
  @ConditionalOnProperty(name = "simplematch.query-service.redis.enabled", havingValue = "true")
  RedisTemplate<String, byte[]> queryRedisTemplate(
      RedisConnectionFactory queryRedisConnectionFactory) {
    final RedisTemplate<String, byte[]> template = new RedisTemplate<>();
    template.setConnectionFactory(queryRedisConnectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(RedisSerializer.byteArray());
    template.afterPropertiesSet();
    return template;
  }

  /** Creates the optional Redis read-through cache. */
  @Bean
  @ConditionalOnProperty(name = "simplematch.query-service.redis.enabled", havingValue = "true")
  QueryReadCache redisQueryReadCache(
      RedisTemplate<String, byte[]> queryRedisTemplate,
      ObjectMapper objectMapper,
      QueryServiceProperties properties) {
    return new RedisQueryReadCache(
        queryRedisTemplate, objectMapper, properties.redis().keyPrefix());
  }
}
