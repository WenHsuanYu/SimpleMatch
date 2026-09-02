package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.RedisProperties;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.runtime.QueryReadCache;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class QueryServiceRuntimeConfigurationTest {
  @Test
  void cacheMapperSerializesMarketReferenceTradingDay() throws Exception {
    final ObjectMapper mapper = new QueryServiceRuntimeConfiguration().queryServiceObjectMapper();

    final String json =
        mapper.writeValueAsString(
            new QueryMarketReferenceView(
                LocalDate.of(2026, 8, 27),
                "2026-08-27:artifact",
                "XTAI",
                "2330",
                "REGULAR",
                1_000L,
                900L,
                1_100L,
                3,
                1_725_000_000_000L));

    assertThat(json).contains("\"tradingDay\":\"2026-08-27\"");
  }

  @Test
  void redisCacheRoundTripsMarketReferenceWithConfiguredMapper() {
    final ValueOperations<String, byte[]> valueOperations = mock(ValueOperations.class);
    final RedisTemplate<String, byte[]> redisTemplate =
        new RedisTemplate<>() {
          @Override
          public ValueOperations<String, byte[]> opsForValue() {
            return valueOperations;
          }
        };
    final QueryServiceRuntimeConfiguration configuration = new QueryServiceRuntimeConfiguration();
    final QueryReadCache cache =
        configuration.redisQueryReadCache(
            redisTemplate,
            configuration.queryServiceObjectMapper(),
            new QueryServiceProperties(null, null, null, null, null));
    final String key = "query:v1:market-reference:2026-08-27:XTAI:2330";
    final QueryMarketReferenceView reference =
        new QueryMarketReferenceView(
            LocalDate.of(2026, 8, 27),
            "2026-08-27:artifact",
            "XTAI",
            "2330",
            "REGULAR",
            1_000L,
            900L,
            1_100L,
            3,
            1_725_000_000_000L);

    cache.put(key, reference);

    final ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    verify(valueOperations).set(eq(key), payload.capture(), eq(Duration.ofSeconds(30)));
    when(valueOperations.get(key)).thenReturn(payload.getValue());

    assertThat(cache.get(key)).isPresent();
    assertThat(cache.get(key).orElseThrow().path("tradingDay").asText())
        .isEqualTo("2026-08-27");
  }

  @Test
  void redisConnectionFactoryUsesBoundedFailureTimeouts() {
    final QueryServiceProperties properties =
        new QueryServiceProperties(
            null,
            null,
            new QueryServiceProperties.Redis(
                true, "query:v1", Duration.ofSeconds(2), Duration.ofMillis(250)),
            null,
            null);
    final QueryServiceRuntimeConfiguration configuration =
        new QueryServiceRuntimeConfiguration();
    final LettuceConnectionFactory factory =
        (LettuceConnectionFactory)
            configuration.queryRedisConnectionFactory(
                new RedisProperties("redis.example:6379"), properties);

    assertThat(factory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(factory.getClientConfiguration().getClientOptions())
        .isPresent()
        .get()
        .extracting(options -> options.getSocketOptions().getConnectTimeout())
        .isEqualTo(Duration.ofMillis(250));

    factory.destroy();
  }

  @Test
  void rejectsNonPositiveRedisFailureTimeouts() {
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.Redis(
                    true, "query:v1", Duration.ZERO, Duration.ofMillis(250)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandTimeout must be positive");
  }

  @Test
  void bindsRedisFailureTimeoutsFromCanonicalSpringNamespace() {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "query-timeouts",
                java.util.Map.of(
                    "simplematch.query-service.redis.enabled", "true",
                    "simplematch.query-service.redis.key-prefix", "query:v1",
                    "simplematch.query-service.redis.command-timeout", "3s",
                    "simplematch.query-service.redis.connect-timeout", "700ms")));

    final QueryServiceProperties properties =
        Binder.get(environment)
            .bind(
                "simplematch.query-service", Bindable.of(QueryServiceProperties.class))
            .orElseThrow(() -> new IllegalStateException("query properties should bind"));

    assertThat(properties.redis().commandTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(properties.redis().connectTimeout()).isEqualTo(Duration.ofMillis(700));
  }

  @Test
  void appliesSafeDefaultsAndRejectsUnusableRedisTimeouts() {
    final QueryServiceProperties.Redis defaults =
        new QueryServiceProperties.Redis(true, "query:v1");

    assertThat(defaults.commandTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofMillis(500));
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.Redis(
                    true, "query:v1", Duration.ofNanos(1), Duration.ofMillis(250)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandTimeout must be at least 1ms");
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.Redis(
                    true, "query:v1", Duration.ofSeconds(2), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectTimeout must be positive");
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.Redis(
                    true, "query:v1", Duration.ofSeconds(11), Duration.ofMillis(250)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandTimeout must not exceed 10 seconds");
  }
}
