package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class KafkaObservationHttpServerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void servesFreshSnapshotsThroughOneSession() throws Exception {
    final StubSession session = new StubSession();
    final HttpClient client = HttpClient.newHttpClient();

    try (var server = new KafkaObservationHttpServer(session, 0)) {
      server.start();
      final URI base = URI.create("http://127.0.0.1:" + server.port());

      final HttpResponse<String> health = client.send(
          HttpRequest.newBuilder(base.resolve("/health")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("READY");

      final JsonNode logEnds = post(client, base.resolve("/log-end-positions"));
      assertThat(logEnds.path("matchingCommands").path("topic").asText())
          .isEqualTo("matching.commands");
      assertThat(logEnds.path("matchingEvents").path("partitions").size()).isEqualTo(15);
      assertThat(session.logEndCaptures).isEqualTo(1);

      final JsonNode committed = post(client, base.resolve("/matching-committed-positions"));
      assertThat(committed.path("topic").asText()).isEqualTo("matching.commands");
      assertThat(committed.path("partitions").get(14).path("committedOffset").asLong())
          .isEqualTo(114L);
      assertThat(session.committedCaptures).isEqualTo(1);
    }

    assertThat(session.closed).isTrue();
  }

  private JsonNode post(HttpClient client, URI uri) throws Exception {
    final HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private static final class StubSession implements KafkaObservationSession {
    private int logEndCaptures;
    private int committedCaptures;
    private boolean closed;

    @Override
    public LogEndPositions captureLogEndPositions() {
      logEndCaptures++;
      return new LogEndPositions(
          new TopicEndPositions("matching.commands", endOffsets(10L)),
          new TopicEndPositions("matching.events", endOffsets(20L)));
    }

    @Override
    public MatchingCommittedPositions captureMatchingCommittedPositions() {
      committedCaptures++;
      final List<PartitionCommittedOffset> partitions = IntStream.range(0, 15)
          .mapToObj(partition -> new PartitionCommittedOffset(partition, 100L + partition))
          .toList();
      return new MatchingCommittedPositions("matching.commands", partitions);
    }

    @Override
    public void close() {
      closed = true;
    }

    private static List<PartitionEndOffset> endOffsets(long base) {
      return IntStream.range(0, 15)
          .mapToObj(partition -> new PartitionEndOffset(partition, base + partition))
          .toList();
    }
  }
}
