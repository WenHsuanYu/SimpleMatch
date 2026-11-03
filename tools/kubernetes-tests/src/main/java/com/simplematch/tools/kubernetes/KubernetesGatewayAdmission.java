package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Opens the run-owned Gateway through its authenticated live admission boundary. */
final class KubernetesGatewayAdmission {
  private final String token = UUID.randomUUID().toString();
  private final ObjectMapper json = new ObjectMapper();

  /** Uses the historical baseline's manual lifecycle; automatic-close has its own owner. */
  String properties() {
    return String.join("\n",
        "simplematch.quickfix-gateway.operations.http-enabled=true",
        "simplematch.quickfix-gateway.operations.automatic-close-enabled=false",
        "simplematch.quickfix-gateway.operations.operator-token=" + token, "");
  }

  JsonNode open(KubernetesScenarioTarget target) throws Exception {
    final String pod = target.gatewayPod();
    try (var forward = target.client().forward("pod/" + pod, 8080,
        target.run().evidence().resolve("gateway-management-forward.log"))) {
      final var result = open(forward.port(), Duration.ofMinutes(2));
      Files.writeString(target.run().evidence().resolve("gateway-open.json"),
          DiagnosticText.redact(result.toPrettyString()));
      return result;
    }
  }

  JsonNode open(int port, Duration budget) throws Exception {
    final long deadline = deadline(budget);
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      do {
        final var status = request(client, port, "status", deadline);
        if (isOpen(status)) {
          return status;
        }
        if (status.path("tradingSystemStatus").path("openEligible").asBoolean()) {
          final var result = request(client, port, "open", deadline);
          if (result.path("accepted").asBoolean() && isOpen(result)) {
            return result;
          }
        }
        Thread.sleep(Duration.ofNanos(Math.min(Duration.ofMillis(500).toNanos(),
            Math.max(0, deadline - System.nanoTime()))));
      } while (System.nanoTime() < deadline);
    }
    throw new TimeoutException("Gateway did not accept OPEN from its live observations");
  }

  private static long deadline(Duration budget) {
    if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Gateway admission requires a positive bounded budget");
    }
    return System.nanoTime() + budget.toNanos();
  }

  private JsonNode request(HttpClient client, int port, String operation, long deadline)
      throws Exception {
    final long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException("Gateway admission budget expired");
    }
    final var request = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/operations/" + operation))
        .timeout(Duration.ofNanos(Math.min(Duration.ofSeconds(5).toNanos(), remaining)))
        .header("X-SimpleMatch-Operator-Token", token);
    if ("open".equals(operation)) {
      request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
          "{\"actor\":\"gradle-certification\",\"reason\":\"run-owned public FIX scenario\"}"));
    }
    final var response = client.send(request.build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Gateway operation " + operation + " returned HTTP "
          + response.statusCode());
    }
    final var result = json.readTree(response.body());
    if (result == null || !result.isObject()) {
      throw new IOException("Gateway operation returned no status object");
    }
    return result;
  }

  private static boolean isOpen(JsonNode result) {
    return "OPEN".equals(result.path("gateState").asText())
        && result.path("tradingSystemStatus").path("openEligible").asBoolean();
  }
}
