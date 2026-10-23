package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.queryservice.QueryServiceApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.web-application-type=servlet",
      "server.address=127.0.0.1",
      "simplematch.query-service.rebuild.http-enabled=false"
    })
@ActiveProfiles("test")
@Import(QueryHttpSecurityIntegrationTest.DatabaseConfiguration.class)
class QueryDirectHttpTest {
  private static final QuerySecurityFixtures FIXTURES = new QuerySecurityFixtures();
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    FIXTURES.register(registry);
  }

  @Test
  void disabledRebuildRetains404ThroughTheActualServletErrorDispatch() throws Exception {
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/query/rebuild"))
            .timeout(Duration.ofSeconds(5))
            .header("X-SimpleMatch-Query-Token", FIXTURES.token("operations"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void directRequestsStillRequireAuthenticationAndExplicitOperationsPermission() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertThat(client.send(freshness(null), HttpResponse.BodyHandlers.discarding()).statusCode())
          .isEqualTo(401);
      assertThat(
              client
                  .send(freshness("client-a"), HttpResponse.BodyHandlers.discarding())
                  .statusCode())
          .isEqualTo(403);
      final var allowed =
          client.send(freshness("operations"), HttpResponse.BodyHandlers.discarding());
      assertThat(allowed.statusCode()).isEqualTo(200);
      assertThat(allowed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
      assertThat(allowed.headers().firstValue("Set-Cookie")).isEmpty();
    }
  }

  @Test
  void callerHeadersCannotTurnAnExternalErrorPathIntoATrustedErrorDispatch() throws Exception {
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/error"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + FIXTURES.token("operations"))
            .header("X-Dispatcher-Type", "ERROR")
            .GET()
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
          .isEqualTo(403);
    }
  }

  private HttpRequest freshness(String alias) {
    final var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/freshness"))
            .timeout(Duration.ofSeconds(5));
    if (alias != null) {
      request.header("Authorization", "Bearer " + FIXTURES.token(alias));
    }
    return request.GET().build();
  }
}
