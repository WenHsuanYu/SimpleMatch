package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.RedisProperties;
import io.lettuce.core.SslVerifyMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class QueryRedisConnectionConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void managedCacheUsesAuthenticatedTlsAndItsOwnEndpoint() throws Exception {
    final Path ca = Files.createFile(temporaryDirectory.resolve("ca.crt"));
    final QueryServiceProperties properties = secureProperties(ca, "query-cache", "test-password");
    final LettuceConnectionFactory factory = factory(properties, "production");

    assertThat(factory.getHostName()).isEqualTo("query-redis.example");
    assertThat(factory.getPort()).isEqualTo(6380);
    assertThat(factory.getStandaloneConfiguration().getUsername()).isEqualTo("query-cache");
    assertThat(factory.getStandaloneConfiguration().getPassword().get())
        .containsExactly("test-password".toCharArray());
    assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
    assertThat(factory.getClientConfiguration().getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
    assertThat(factory.getClientConfiguration().isStartTls()).isFalse();
    assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow().getSslOptions())
        .isNotNull();
    assertThat(factory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofSeconds(2));
    factory.destroy();
  }

  @ParameterizedTest
  @ValueSource(strings = {"staging", "production"})
  void managedCacheRejectsPlaintextEvenWhenOtherConfigurationOverridesIt(String environment) {
    final QueryServiceProperties properties =
        new QueryServiceProperties(null, null, null, null, null);

    assertThatThrownBy(() -> factory(properties, environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authenticated TLS");
  }

  @Test
  void localCacheRetainsSharedEndpointAndBoundedFailureTimeouts() {
    final QueryServiceProperties properties =
        new QueryServiceProperties(
            null,
            null,
            new QueryServiceProperties.Redis(
                true, "query:v1", Duration.ofSeconds(2), Duration.ofMillis(250)),
            null,
            null);
    final LettuceConnectionFactory factory = factory(properties, "local");

    assertThat(factory.getHostName()).isEqualTo("shared-redis.example");
    assertThat(factory.getPort()).isEqualTo(6379);
    assertThat(factory.getClientConfiguration().isUseSsl()).isFalse();
    assertThat(
            factory
                .getClientConfiguration()
                .getClientOptions()
                .orElseThrow()
                .getSocketOptions()
                .getConnectTimeout())
        .isEqualTo(Duration.ofMillis(250));
    factory.destroy();
  }

  @Test
  void bindsSecretBackedSecurityWithoutExposingPasswordInDiagnostics() {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "query-redis",
                Map.of(
                    "simplematch.query-service.redis.enabled", "true",
                    "simplematch.query-service.redis.key-prefix", "query:v2:tls",
                    "simplematch.query-service.redis.security.tls-enabled", "true",
                    "simplematch.query-service.redis.security.trust-certificate-path",
                        "/etc/redis/ca.crt",
                    "simplematch.query-service.redis.security.username", "query-cache",
                    "simplematch.query-service.redis.security.password", "test-password")));

    final QueryServiceProperties properties =
        Binder.get(environment)
            .bind("simplematch.query-service", Bindable.of(QueryServiceProperties.class))
            .orElseThrow(() -> new IllegalStateException("query properties should bind"));

    assertThat(properties.redis().security().tlsEnabled()).isTrue();
    assertThat(properties.redis().security().password()).isEqualTo("test-password");
    assertThat(properties.toString()).doesNotContain("test-password");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "default"})
  void tlsRequiresDedicatedCredentials(String username) {
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.RedisSecurity(
                    true, "/etc/redis/ca.crt", username, "test-password"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dedicated");
  }

  @Test
  void rejectsMissingPasswordAndTrustMaterialAndPlaintextCredentials() {
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.RedisSecurity(
                    true, "/etc/redis/ca.crt", "query-cache", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.RedisSecurity(true, "", "query-cache", "test-password"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new QueryServiceProperties.RedisSecurity(false, "", "query-cache", "test-password"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "redis://host:6380",
        "user:password@host:6380",
        "host:6380/path",
        "host:6380?tls=false",
        "host:0",
        "host:65536",
        "host:abc",
        "host:6380,other:6380"
      })
  void rejectsAmbiguousOrInvalidDedicatedEndpoints(String endpoint) {
    final QueryServiceProperties properties =
        new QueryServiceProperties(
            null,
            null,
            new QueryServiceProperties.Redis(true, "query:v1", null, null, endpoint, null),
            null,
            null);

    assertThatThrownBy(() -> factory(properties, "local"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private QueryServiceProperties secureProperties(Path ca, String username, String password) {
    return new QueryServiceProperties(
        null,
        null,
        new QueryServiceProperties.Redis(
            true,
            "query:v2:tls",
            null,
            null,
            "query-redis.example:6380",
            new QueryServiceProperties.RedisSecurity(true, ca.toString(), username, password)),
        null,
        null);
  }

  private LettuceConnectionFactory factory(QueryServiceProperties properties, String environment) {
    return (LettuceConnectionFactory)
        new QueryRedisConnectionConfiguration()
            .queryRedisConnectionFactory(
                new RedisProperties("shared-redis.example:6379"),
                properties,
                new EnvironmentProperties(environment));
  }
}
