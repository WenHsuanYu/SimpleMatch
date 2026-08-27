package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** HTTP adapter exposing one warm {@link KafkaObservationSession} inside the helper Pod. */
final class KafkaObservationHttpServer implements AutoCloseable {
  private final KafkaObservationSession session;
  private final ObjectMapper mapper;
  private final HttpServer server;
  private final ExecutorService executor;

  KafkaObservationHttpServer(KafkaObservationSession session, int port) {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be between 0 and 65535");
    }
    this.session = Objects.requireNonNull(session, "session is required");
    mapper = new ObjectMapper();
    try {
      server = HttpServer.create(new InetSocketAddress(port), 0);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot bind Kafka observation HTTP server", failure);
    }
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"READY\"}"));
    server.createContext(
        "/log-end-positions",
        exchange -> handleSnapshot(exchange, session::captureLogEndPositions));
    server.createContext(
        "/matching-committed-positions",
        exchange -> handleSnapshot(exchange, session::captureMatchingCommittedPositions));
    server.createContext("/close-barriers", this::handleCloseBarriers);
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
    session.close();
  }

  private void handleSnapshot(HttpExchange exchange, Supplier<Object> snapshotSupplier)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"POST required\"}");
      return;
    }
    try {
      respond(exchange, 200, mapper.writeValueAsString(snapshotSupplier.get()));
    } catch (RuntimeException failure) {
      respond(exchange, 503, "{\"error\":\"Kafka observation unavailable\"}");
    }
  }

  private void handleCloseBarriers(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"POST required\"}");
      return;
    }
    try {
      final KafkaObservationSession.CloseBarrierExpectation expectation =
          mapper.readValue(
              exchange.getRequestBody(),
              KafkaObservationSession.CloseBarrierExpectation.class);
      respond(exchange, 200, mapper.writeValueAsString(session.verifyCloseBarriers(expectation)));
    } catch (JacksonException | IllegalArgumentException failure) {
      respond(exchange, 400, "{\"error\":\"invalid Close Barrier expectation\"}");
    } catch (RuntimeException failure) {
      respond(exchange, 503, "{\"error\":\"Close Barrier observation unavailable\"}");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
