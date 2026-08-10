package com.simplematch.marketreference.builder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Fetches exact official JSON bytes through the offline builder's replaceable transport boundary.
 */
public final class HttpOfficialSourceTransport implements OfficialSourceTransport {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private final HttpClient httpClient;
  private final Clock clock;

  /** Creates an official HTTP transport with an injected client and clock. */
  public HttpOfficialSourceTransport(HttpClient httpClient, Clock clock) {
    this.httpClient = Objects.requireNonNull(httpClient, "HTTP client is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /** Retrieves one official endpoint once and fails closed for non-success responses. */
  @Override
  public RetrievedOfficialSource retrieve(OfficialSourceType sourceType) {
    Objects.requireNonNull(sourceType, "source type is required");
    final HttpRequest request =
        HttpRequest.newBuilder(sourceType.endpoint())
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    try {
      final HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new MarketReferenceBuildException(
            "official source request failed with HTTP status " + response.statusCode());
      }
      return new RetrievedOfficialSource(
          sourceType, sourceType.endpoint(), clock.instant(), response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new MarketReferenceBuildException("official source request was interrupted", exception);
    } catch (IOException exception) {
      throw new MarketReferenceBuildException("official source request failed", exception);
    }
  }
}
