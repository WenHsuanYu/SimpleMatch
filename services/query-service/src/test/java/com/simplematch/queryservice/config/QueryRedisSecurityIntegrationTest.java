package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.RedisProperties;
import com.simplematch.queryservice.runtime.RedisQueryReadCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Tests Query TLS and ACL behavior with disposable Redis containers and private certificate files.
 */
class QueryRedisSecurityIntegrationTest {
  private static final int REDIS_PORT = 6379;
  @TempDir private static Path fixtureDirectory;
  @AutoClose private static GenericContainer<?> trusted;
  @AutoClose private static GenericContainer<?> mismatch;
  @AutoClose private static GenericContainer<?> untrusted;
  private static RedisTlsCertificates certificates;
  private final List<LettuceConnectionFactory> factories = new ArrayList<>();

  @BeforeAll
  static void startRedis() throws IOException, InterruptedException {
    certificates = RedisTlsCertificates.create(fixtureDirectory);
    trusted = redis("trusted");
    mismatch = redis("mismatch");
    untrusted = redis("untrusted");
    Stream.of(trusted, mismatch, untrusted).forEach(GenericContainer::start);
  }

  @AfterEach
  void destroyClients() {
    factories.forEach(LettuceConnectionFactory::destroy);
  }

  @Test
  void trustedAuthenticatedCacheRoundTripsExpiresEvictsInvalidJsonAndClearsInBatches() {
    final LettuceConnectionFactory factory =
        client(trusted, "query-cache", certificates.password());
    final RedisTemplate<String, byte[]> template = template(factory);
    final RedisQueryReadCache cache = cache(template);
    final RedisTemplate<String, byte[]> admin =
        template(client(trusted, "test-admin", certificates.adminPassword()));
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
    final LettuceConnectionFactory factory =
        client(trusted, "query-cache", certificates.password());
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
  @MethodSource("rejectedServers")
  void rejectsEndpointImpersonation(GenericContainer<?> server) {
    final LettuceConnectionFactory factory = client(server, "query-cache", certificates.password());

    assertThatThrownBy(() -> template(factory).opsForValue().get("query:v2:tls:order:1"))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("SSLHandshakeException");
  }

  @Test
  void rejectsWrongCredentialsAndServerRejectsUnauthenticatedClients() {
    final LettuceConnectionFactory wrong = client(trusted, "query-cache", "wrong-test-password");
    assertThatThrownBy(() -> template(wrong).opsForValue().get("query:v2:tls:order:1"))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("WRONGPASS");

    final RedisStandaloneConfiguration server =
        new RedisStandaloneConfiguration(trusted.getHost(), trusted.getMappedPort(REDIS_PORT));
    final LettuceConnectionFactory anonymous =
        new LettuceConnectionFactory(server, wrong.getClientConfiguration());
    factories.add(anonymous);
    anonymous.afterPropertiesSet();

    assertThatThrownBy(() -> template(anonymous).opsForValue().get("query:v2:tls:order:1"))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("NOAUTH");
  }

  @Test
  void untrustedCertificateCannotAcceptCacheWrites() {
    final RedisQueryReadCache cache =
        cache(template(client(untrusted, "query-cache", certificates.password())));
    assertThatThrownBy(
            () -> cache.put("query:v2:tls:order:1", java.util.Map.of("state", "RESTING")))
        .isInstanceOf(RedisConnectionFailureException.class)
        .hasStackTraceContaining("SSLHandshakeException");
  }

  private RedisQueryReadCache cache(RedisTemplate<String, byte[]> template) {
    return new RedisQueryReadCache(template, new ObjectMapper(), "query:v2:tls");
  }

  private RedisTemplate<String, byte[]> template(LettuceConnectionFactory factory) {
    return new QueryServiceRuntimeConfiguration().queryRedisTemplate(factory);
  }

  private LettuceConnectionFactory client(
      GenericContainer<?> server, String username, String password) {
    final LettuceConnectionFactory factory =
        (LettuceConnectionFactory)
            new QueryRedisConnectionConfiguration()
                .queryRedisConnectionFactory(
                    new RedisProperties("unused:6379"),
                    properties(server, username, password),
                    new EnvironmentProperties("production"));
    factories.add(factory);
    factory.afterPropertiesSet();
    return factory;
  }

  private QueryServiceProperties properties(
      GenericContainer<?> container, String username, String password) {
    return new QueryServiceProperties(
        null,
        null,
        new QueryServiceProperties.Redis(
            true,
            "query:v2:tls",
            null,
            null,
            container.getHost() + ":" + container.getMappedPort(REDIS_PORT),
            new QueryServiceProperties.RedisSecurity(
                true, certificates.directory().resolve("ca.crt").toString(), username, password)),
        null,
        null);
  }

  /** Copies only the server credentials and configures one bounded TLS endpoint. */
  private static GenericContainer<?> redis(String identity) {
    final Path directory = certificates.directory();
    return new GenericContainer<>("redis:8.10.3-alpine")
        .withLabel("simplematch.test", "s2-c-query-redis")
        .withLabel("simplematch.fixture", directory.getFileName().toString())
        .withCopyFileToContainer(
            MountableFile.forHostPath(directory.resolve(identity + ".crt"), 0444),
            "/tls/server.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(directory.resolve(identity + ".key"), 0444),
            "/tls/server.key")
        .withCopyFileToContainer(
            MountableFile.forHostPath(directory.resolve("ca.crt"), 0444), "/tls/ca.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(directory.resolve("users.acl"), 0444), "/tls/users.acl")
        .withExposedPorts(REDIS_PORT)
        .withCommand(
            "redis-server",
            "--bind",
            "0.0.0.0",
            "--port",
            "0",
            "--tls-port",
            "6379",
            "--tls-cert-file",
            "/tls/server.crt",
            "--tls-key-file",
            "/tls/server.key",
            "--tls-ca-cert-file",
            "/tls/ca.crt",
            "--tls-auth-clients",
            "no",
            "--aclfile",
            "/tls/users.acl",
            "--save",
            "",
            "--appendonly",
            "no")
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(30));
  }

  private static Stream<Named<GenericContainer<?>>> rejectedServers() {
    return Stream.of(
        Named.of("hostname mismatch", mismatch), Named.of("untrusted issuer", untrusted));
  }

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
