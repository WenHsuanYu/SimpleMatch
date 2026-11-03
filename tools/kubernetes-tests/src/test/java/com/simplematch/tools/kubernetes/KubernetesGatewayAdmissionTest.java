package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Checks the transport contract while Gateway's existing tests own its admission rules. */
class KubernetesGatewayAdmissionTest {
  @Test
  void usesTheProvisionedCredentialAndActualOperatorResult() throws Exception {
    final var admission = new KubernetesGatewayAdmission();
    final var properties = new Properties();
    properties.load(new StringReader(admission.properties()));
    final String token = properties.getProperty(
        "simplematch.quickfix-gateway.operations.operator-token");
    final var posts = new AtomicInteger();
    final var server = HttpServer.create(new InetSocketAddress(
        InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
    server.createContext("/operations", exchange -> {
      if (!token.equals(exchange.getRequestHeaders().getFirst("X-SimpleMatch-Operator-Token"))) {
        reply(exchange, 401, "{}");
        return;
      }
      if ("/operations/status".equals(exchange.getRequestURI().getPath())) {
        reply(exchange, 200, """
            {"gateState":"PRE_OPEN","tradingSystemStatus":{"openEligible":true}}
            """);
      } else if ("POST".equals(exchange.getRequestMethod())) {
        posts.incrementAndGet();
        reply(exchange, 200, """
            {"accepted":true,"gateState":"OPEN","tradingSystemStatus":{"openEligible":true}}
            """);
      } else {
        reply(exchange, 405, "{}");
      }
    });
    server.start();
    try {
      final var result = admission.open(server.getAddress().getPort(), Duration.ofSeconds(5));
      assertEquals("OPEN", result.path("gateState").asText());
      assertEquals(1, posts.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void anUnauthorizedStatusFailsWithoutOpeningOrPublishingCredentials() throws Exception {
    final var admission = new KubernetesGatewayAdmission();
    final var server = HttpServer.create(new InetSocketAddress(
        InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
    final var posts = new AtomicInteger();
    server.createContext("/operations/status", exchange -> reply(exchange, 401, "private-body"));
    server.createContext("/operations/open", exchange -> {
      posts.incrementAndGet();
      reply(exchange, 200, "{}");
    });
    server.start();
    try {
      final var failure = assertThrows(IOException.class,
          () -> admission.open(server.getAddress().getPort(), Duration.ofSeconds(5)));
      assertEquals("Gateway operation status returned HTTP 401", failure.getMessage());
      assertEquals(0, posts.get());
    } finally {
      server.stop(0);
    }
  }

  private static void reply(HttpExchange exchange, int code, String text) throws IOException {
    final byte[] body = text.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.sendResponseHeaders(code, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
