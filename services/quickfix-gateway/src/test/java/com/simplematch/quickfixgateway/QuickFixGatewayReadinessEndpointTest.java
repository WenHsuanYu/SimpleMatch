package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "simplematch.quickfix-gateway.data-plane-enabled=false",
      "simplematch.quickfix-gateway.replay-enabled=false",
      "simplematch.quickfix-gateway.operations.monitor-enabled=false",
      "spring.flyway.enabled=false",
      "spring.kafka.listener.auto-startup=false",
      "management.health.db.enabled=false"
    })
@ActiveProfiles("test")
class QuickFixGatewayReadinessEndpointTest {
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @DisplayName("the gateway exposes healthz and readyz actuator endpoints")
  @Test
  void gatewayExposesHealthzAndReadyzActuatorEndpoints() throws Exception {
    final HttpResponse<String> healthResponse = get("/healthz");
    final HttpResponse<String> readinessResponse = get("/readyz");
    final HttpResponse<String> metricsResponse = get("/metrics");

    assertThat(healthResponse.statusCode())
        .withFailMessage("health endpoint response: %s", healthResponse.body())
        .isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(healthResponse.body()).contains("UP");
    assertThat(readinessResponse.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(readinessResponse.body()).contains("UP");
    assertThat(metricsResponse.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
    assertThat(metricsResponse.body()).contains("names");
  }

  private HttpResponse<String> get(String path) throws Exception {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }
}
