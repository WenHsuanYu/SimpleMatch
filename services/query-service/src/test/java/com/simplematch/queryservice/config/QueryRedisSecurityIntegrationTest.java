package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.RedisProperties;
import com.simplematch.queryservice.model.QueryFreshness;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.runtime.RedisQueryReadCache;
import com.simplematch.queryservice.store.QueryProjectionStore;
import com.simplematch.queryservice.web.QueryReadController;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;

/** Network regression gate run only by scripts/test-query-redis-security.sh. */
class QueryRedisSecurityIntegrationTest {
  private final List<LettuceConnectionFactory> factories = new ArrayList<>();

  @AfterEach
  void destroyClients() {
    factories.forEach(LettuceConnectionFactory::destroy);
  }

  @Test
  void trustedAuthenticatedCacheRoundTripsExpiresEvictsInvalidJsonAndClearsInBatches() {
    final LettuceConnectionFactory factory = client("PORT", "query-cache", required("PASSWORD"));
    final RedisTemplate<String, byte[]> template = template(factory);
    final RedisQueryReadCache cache = cache(template);
    final RedisTemplate<String, byte[]> admin =
        template(client("PORT", "test-admin", required("ADMIN_PASSWORD")));
    admin.opsForValue().set("other-service:keep", bytes("private"));
    cache.clear();

    cache.put("query:v2:tls:order:1", java.util.Map.of("state", "RESTING"));

    assertThat(cache.get("query:v2:tls:order:1").orElseThrow().path("state").asText())
        .isEqualTo("RESTING");
    assertThat(admin.getExpire("query:v2:tls:order:1", TimeUnit.SECONDS)).isBetween(1L, 30L);
    template.opsForValue().set("query:v2:tls:invalid", bytes("invalid JSON"));
    assertThat(cache.get("query:v2:tls:invalid")).isEmpty();
    assertThat(template.opsForValue().get("query:v2:tls:invalid")).isNull();
    for (int index = 0; index < 205; index++) {
      cache.put("query:v2:tls:batch:" + index, index);
    }

    cache.clear();

    for (int index = 0; index < 205; index++) {
      assertThat(cache.get("query:v2:tls:batch:" + index)).isEmpty();
    }
    assertThat(cache.get("query:v2:tls:order:1")).isEmpty();
    assertThat(admin.opsForValue().get("other-service:keep")).isEqualTo(bytes("private"));
    admin.delete("other-service:keep");
  }

  @Test
  void queryIdentityCannotAccessOtherValuesEnumerateWithKeysOrAdministerRedis() {
    final LettuceConnectionFactory factory = client("PORT", "query-cache", required("PASSWORD"));
    final RedisTemplate<String, byte[]> template = template(factory);

    assertThatThrownBy(() -> template.opsForValue().get("other-service:secret"))
        .hasStackTraceContaining("NOPERM");
    assertThatThrownBy(() -> template.opsForValue().set("other-service:secret", bytes("poison")))
        .hasStackTraceContaining("NOPERM");
    assertThatThrownBy(() -> template.delete("other-service:secret"))
        .hasStackTraceContaining("NOPERM");
    assertThatThrownBy(() -> template.keys("*")).hasStackTraceContaining("NOPERM");
    try (RedisConnection connection = factory.getConnection()) {
      assertThatThrownBy(() -> connection.execute("CONFIG", bytes("GET"), bytes("*")))
          .hasStackTraceContaining("NOPERM");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISMATCH_PORT", "UNTRUSTED_PORT"})
  void rejectsEndpointImpersonation(String port) {
    final LettuceConnectionFactory factory = client(port, "query-cache", required("PASSWORD"));

    assertThatThrownBy(() -> template(factory).opsForValue().get("query:v2:tls:order:1"))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("SSLHandshakeException");
  }

  @Test
  void rejectsWrongCredentialsAndServerRejectsUnauthenticatedClients() {
    final LettuceConnectionFactory wrong = client("PORT", "query-cache", "wrong-test-password");
    assertThatThrownBy(() -> template(wrong).opsForValue().get("query:v2:tls:order:1"))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("WRONGPASS");

    final RedisStandaloneConfiguration server =
        new RedisStandaloneConfiguration("localhost", Integer.parseInt(required("PORT")));
    final LettuceConnectionFactory anonymous =
        new LettuceConnectionFactory(server, wrong.getClientConfiguration());
    factories.add(anonymous);
    anonymous.afterPropertiesSet();

    assertThatThrownBy(() -> template(anonymous).opsForValue().get("query:v2:tls:order:1"))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("NOAUTH");
  }

  @Test
  void certificateFailureRetainsTheLegacyMarketAdaptersDurableResponse() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryMarketReferenceView reference = new QueryMarketReferenceView(
        LocalDate.of(2026, 8, 27), "fixture-artifact", "XTAI", "2330", "rule",
        100L, 90L, 110L, 0, 1L);
    when(store.findMarketReference("2026-08-27", "XTAI", "2330"))
        .thenReturn(Optional.of(reference));
    when(store.freshness()).thenReturn(new QueryFreshness(List.of()));
    final RedisQueryReadCache cache =
        cache(template(client("UNTRUSTED_PORT", "query-cache", required("PASSWORD"))));
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("cache", cache);
    final QueryReadController controller =
        new QueryReadController(
            store,
            beans.getBeanProvider(QueryReadCache.class),
            properties("PORT", "query-cache", required("PASSWORD")));

    final org.springframework.http.ResponseEntity<?> response =
        controller.marketReference("2026-08-27", "XTAI", "2330");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .isInstanceOf(com.simplematch.queryservice.runtime.QueryReadResponse.class);
  }

  private RedisQueryReadCache cache(RedisTemplate<String, byte[]> template) {
    return new RedisQueryReadCache(template, new ObjectMapper(), "query:v2:tls");
  }

  private RedisTemplate<String, byte[]> template(LettuceConnectionFactory factory) {
    return new QueryServiceRuntimeConfiguration().queryRedisTemplate(factory);
  }

  private LettuceConnectionFactory client(String port, String username, String password) {
    final LettuceConnectionFactory factory =
        (LettuceConnectionFactory)
            new QueryRedisConnectionConfiguration()
                .queryRedisConnectionFactory(
                    new RedisProperties("unused:6379"),
                    properties(port, username, password),
                    new EnvironmentProperties("production"));
    factories.add(factory);
    factory.afterPropertiesSet();
    return factory;
  }

  private QueryServiceProperties properties(String port, String username, String password) {
    return new QueryServiceProperties(
        null,
        null,
        new QueryServiceProperties.Redis(
            true,
            "query:v2:tls",
            null,
            null,
            "localhost:" + required(port),
            new QueryServiceProperties.RedisSecurity(true, required("CA"), username, password)),
        null,
        null);
  }

  private String required(String name) {
    final String value = System.getenv("SIMPLEMATCH_REDIS_TEST_" + name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Use scripts/test-query-redis-security.sh to supply Redis fixtures");
    }
    return value;
  }

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
