package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.runtime.QueryReadCache;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
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
}
