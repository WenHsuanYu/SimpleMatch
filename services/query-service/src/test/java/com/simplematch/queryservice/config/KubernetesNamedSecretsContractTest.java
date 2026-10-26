package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.kubernetes.client.config.KubernetesClientSecretsPropertySourceLocator;
import org.springframework.cloud.kubernetes.commons.KubernetesNamespaceProvider;
import org.springframework.cloud.kubernetes.commons.config.SecretsConfigProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Exercises actual service YAML and the real Kubernetes Secret locator under named-GET-only RBAC. */
class KubernetesNamedSecretsContractTest {
  private static final String READ_TYPE = "spring.cloud.kubernetes.secrets.read-type";

  @ParameterizedTest
  @ValueSource(strings = {"query-service", "quickfix-gateway"})
  void actualServiceConfigurationLoadsSecurityWithoutListingSecrets(String application) throws Exception {
    final StandardEnvironment environment = environment(application, false);
    final String key = markerKey(application);
    final String expected = application.equals("query-service") ? "1" : UUID.randomUUID().toString();
    try (NamedSecretApi api = new NamedSecretApi(application, key + "=" + expected + "\n")) {
      final PropertySource<?> source = locate(environment, api);

      assertThat(api.requests()).containsExactly("/api/v1/namespaces/synthetic/secrets/" + application + "-secrets");
      assertThat(expected.equals(source.getProperty(key)))
          .as("Actual service configuration must load security from the allowed named Secret")
          .isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"query-service", "quickfix-gateway"})
  void omittingSecretReadModeReproducesForbiddenListAndAbsentSecurity(String application) throws Exception {
    final StandardEnvironment environment = environment(application, true);
    try (NamedSecretApi api = new NamedSecretApi(application, markerKey(application) + "=1\n")) {
      final PropertySource<?> source = locate(environment, api);

      assertThat(source.getProperty(markerKey(application)) == null).isTrue();
      assertThat(api.requests()).containsExactly("/api/v1/namespaces/synthetic/secrets");
    }
  }

  private static StandardEnvironment environment(String application, boolean omitReadMode) throws IOException {
    final StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles("local");
    environment.getPropertySources().addFirst(new MapPropertySource("deployed-bootstrap", Map.of(
        "SIMPLEMATCH_KUBERNETES_ENABLED", true,
        "spring.cloud.kubernetes.client.namespace", "synthetic",
        "spring.cloud.kubernetes.secrets.include-profile-specific-sources", false)));
    for (PropertySource<?> source : new YamlPropertySourceLoader().load("service-config",
        new ClassPathResource("kubernetes-secret-contract/" + application + "/application.yaml"))) {
      if (!(source instanceof MapPropertySource yaml)) {
        throw new IllegalStateException("Expected the actual service YAML property source");
      }
      final Map<String, Object> properties = new HashMap<>();
      for (String key : yaml.getPropertyNames()) {
        properties.put(key, yaml.getProperty(key));
      }
      if (omitReadMode) {
        properties.remove(READ_TYPE);
      }
      environment.getPropertySources().addLast(new MapPropertySource(source.getName(), properties));
    }
    assertThat(environment.getProperty("spring.application.name")).isEqualTo(application);
    return environment;
  }

  private static String markerKey(String application) {
    return application.equals("query-service") ? "simplematch.query-service.security.schema-version"
        : "simplematch.quickfix-gateway.fix-security.key-store-password";
  }

  private static PropertySource<?> locate(StandardEnvironment environment, NamedSecretApi api) {
    final SecretsConfigProperties properties = Binder.get(environment)
        .bind(SecretsConfigProperties.PREFIX, Bindable.of(SecretsConfigProperties.class)).get();
    final ApiClient client = new ApiClient();
    client.setBasePath("http://127.0.0.1:" + api.port());
    client.setConnectTimeout(1000);
    client.setReadTimeout(1000);
    try {
      return new KubernetesClientSecretsPropertySourceLocator(new CoreV1Api(client),
          new KubernetesNamespaceProvider(environment), properties).locate(environment);
    } finally {
      client.getHttpClient().dispatcher().executorService().shutdown();
      client.getHttpClient().connectionPool().evictAll();
    }
  }

  /** Real HTTP fixture permits the exact named GET and denies the broader Secret-list operation. */
  private static final class NamedSecretApi implements AutoCloseable {
    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private NamedSecretApi(String application, String properties) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
      final String namedPath = "/api/v1/namespaces/synthetic/secrets/" + application + "-secrets";
      final String secret = "{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":\""
          + application + "-secrets\",\"namespace\":\"synthetic\"},\"data\":{\"postgres_dsn\":\""
          + encoded("synthetic-dsn") + "\",\"" + application + ".properties\":\"" + encoded(properties) + "\"}}";
      server.createContext("/api/v1/namespaces/synthetic/secrets", exchange -> {
        final String path = exchange.getRequestURI().getPath();
        requests.add(path);
        final boolean allowed = exchange.getRequestMethod().equals("GET") && path.equals(namedPath);
        final byte[] body = (allowed ? secret : "{\"kind\":\"Status\",\"status\":\"Failure\","
            + "\"reason\":\"Forbidden\",\"code\":403}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(allowed ? 200 : 403, body.length);
        try (var response = exchange.getResponseBody()) {
          response.write(body);
        } finally {
          exchange.close();
        }
      });
      server.start();
    }

    private int port() {
      return server.getAddress().getPort();
    }

    private List<String> requests() {
      return List.copyOf(requests);
    }

    private static String encoded(String value) {
      return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Stops the fixture without retaining client material or server processes. */
    @Override
    public void close() {
      server.stop(0);
    }
  }
}
