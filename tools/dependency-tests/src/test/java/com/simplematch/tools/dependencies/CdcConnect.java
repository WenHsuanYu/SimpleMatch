package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Uses the actual Connect REST interface with bounded waits and state-only diagnostics. */
final class CdcConnect {
  private final String base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  CdcConnect(String base) {
    this.base = base;
  }

  void register(CdcOwner owner, PostgreSQLContainer postgres) throws Exception {
    final JsonNode document =
        mapper.readTree(
            FlywayOwners.root()
                .resolve("deploy/compose/" + owner.service + "-outbox-connector.json")
                .toFile());
    final ObjectNode configuration = (ObjectNode) document.get("config");
    configuration
        .put("database.hostname", "postgres")
        .put("database.port", "5432")
        .put("database.user", postgres.getUsername())
        .put("database.password", postgres.getPassword())
        .put("database.dbname", postgres.getDatabaseName());
    request("PUT", "/connectors/" + owner.connector() + "/config", configuration.toString());
    awaitState(owner, "RUNNING");
  }

  void pause(CdcOwner owner) throws Exception {
    request("PUT", "/connectors/" + owner.connector() + "/pause", "");
    awaitState(owner, "PAUSED");
  }

  void resume(CdcOwner owner) throws Exception {
    request("PUT", "/connectors/" + owner.connector() + "/resume", "");
    awaitState(owner, "RUNNING");
  }

  void awaitAll() throws Exception {
    for (CdcOwner owner : CdcOwner.values()) {
      awaitState(owner, "RUNNING");
    }
  }

  void awaitState(CdcOwner owner, String expected) throws Exception {
    final Instant deadline = Instant.now().plusSeconds(90);
    String last = "unavailable";
    while (Instant.now().isBefore(deadline)) {
      final var response = getStatus(owner);
      last = response.path("connector").path("state").asText("unavailable");
      boolean tasksMatch = response.path("tasks").isArray() && !response.path("tasks").isEmpty();
      for (JsonNode task : response.path("tasks")) {
        tasksMatch &= task.path("state").asText().equals(expected);
      }
      if (last.equals(expected) && tasksMatch) {
        return;
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException(
        "Connector " + owner.connector() + " did not reach " + expected + "; last state=" + last);
  }

  JsonNode offsets(CdcOwner owner) throws Exception {
    return mapper
        .readTree(request("GET", "/connectors/" + owner.connector() + "/offsets", ""))
        .path("offsets");
  }

  private JsonNode getStatus(CdcOwner owner) throws Exception {
    try {
      return mapper.readTree(request("GET", "/connectors/" + owner.connector() + "/status", ""));
    } catch (java.io.IOException unavailable) {
      return mapper.createObjectNode();
    }
  }

  private String request(String method, String path, String body) throws Exception {
    final var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method(
                method,
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 500
        || (method.equals("GET")
            && (response.statusCode() == 404 || response.statusCode() == 409))) {
      throw new java.io.IOException(
          "Connect temporarily unavailable (HTTP " + response.statusCode() + ")");
    }
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        () -> "Connect " + method + " " + path + " returned HTTP " + response.statusCode());
    return response.body();
  }
}
