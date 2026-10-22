package com.simplematch.queryservice.config;

import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Enforces the transport and identity contract at Query's only Redis connection factory. */
@Configuration(proxyBeanMethods = false)
public class QueryRedisConnectionConfiguration {
  /**
   * Creates the optional cache connection, rejecting transport downgrades in managed environments.
   */
  @Bean(destroyMethod = "destroy")
  @ConditionalOnProperty(name = "simplematch.query-service.redis.enabled", havingValue = "true")
  RedisConnectionFactory queryRedisConnectionFactory(
      RedisProperties sharedProperties,
      QueryServiceProperties queryProperties,
      EnvironmentProperties environment) {
    final QueryServiceProperties.Redis redis = queryProperties.redis();
    final QueryServiceProperties.RedisSecurity security = redis.security();
    if (!"local".equals(environment.environment())
        && !"test".equals(environment.environment())
        && !security.tlsEnabled()) {
      throw new IllegalArgumentException("managed query Redis requires authenticated TLS");
    }
    final RedisStandaloneConfiguration server = serverConfiguration(sharedProperties, redis);
    final ClientOptions.Builder options =
        ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(redis.connectTimeout()).build());
    final LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
        LettuceClientConfiguration.builder().commandTimeout(redis.commandTimeout());
    if (security.tlsEnabled()) {
      server.setUsername(security.username());
      server.setPassword(RedisPassword.of(security.password()));
      options.sslOptions(
          SslOptions.builder()
              .jdkSslProvider()
              .trustManager(Path.of(security.trustCertificatePath()).toFile())
              .build());
      client.useSsl().verifyPeer(SslVerifyMode.FULL);
    }
    return new LettuceConnectionFactory(server, client.clientOptions(options.build()).build());
  }

  private RedisStandaloneConfiguration serverConfiguration(
      RedisProperties sharedProperties, QueryServiceProperties.Redis redis) {
    // The legacy shared list still selects its first endpoint. A dedicated Query endpoint is
    // single.
    final String endpoint =
        redis.endpoint().isEmpty()
            ? sharedProperties.endpoints().split(",", 2)[0].trim()
            : redis.endpoint();
    final URI uri;
    try {
      uri = URI.create("redis://" + endpoint);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("query Redis endpoint must use host:port syntax");
    }
    requireStandaloneEndpoint(uri);
    final String host = uri.getHost();
    return new RedisStandaloneConfiguration(
        host.startsWith("[") ? host.substring(1, host.length() - 1) : host, uri.getPort());
  }

  private void requireStandaloneEndpoint(URI uri) {
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPort() < 1
        || uri.getPort() > 65_535) {
      throw new IllegalArgumentException("query Redis endpoint must use host:port syntax");
    }
  }
}
