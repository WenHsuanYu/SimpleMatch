package com.simplematch.quickfixgateway.operations.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** JDK HTTP adapter with a strict response deadline and fail-closed JSON parsing. */
public final class JdkStatusDocumentClient implements StatusDocumentClient {
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Duration requestTimeout;

  /** Creates the bounded internal status client. */
  public JdkStatusDocumentClient(
      HttpClient httpClient, ObjectMapper objectMapper, Duration requestTimeout) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
  }

  @Override
  public JsonNode read(String endpoint) {
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint)).timeout(requestTimeout).GET().build();
    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "status endpoint returned HTTP " + response.statusCode() + ": " + endpoint);
      }
      return parse(response.body(), endpoint);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("status request interrupted: " + endpoint, failure);
    } catch (IOException failure) {
      throw new IllegalStateException("status request failed: " + endpoint, failure);
    }
  }

  private JsonNode parse(String body, String endpoint) {
    try {
      final JsonNode document = objectMapper.readTree(body);
      if (!document.isObject()) {
        throw new IllegalStateException("status document is not an object: " + endpoint);
      }
      return document;
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("status document is malformed: " + endpoint, failure);
    }
  }
}
