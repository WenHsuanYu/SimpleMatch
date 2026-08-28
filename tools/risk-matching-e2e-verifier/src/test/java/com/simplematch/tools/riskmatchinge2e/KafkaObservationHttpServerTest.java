package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CloseBarrier;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
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

      final String expectation =
          mapper.writeValueAsString(
              new KafkaObservationSession.CloseBarrierExpectation(
                  "2026-08-27-regular",
                  "2026-08-27",
                  "abcdef",
                  "price-time-v1",
                  new KafkaObservationSession.TopicEndPositions(
                      "matching.commands", StubSession.endOffsets(10L)),
                  new KafkaObservationSession.TopicEndPositions(
                      "matching.commands", StubSession.endOffsets(11L))));
      final JsonNode barriers = post(client, base.resolve("/close-barriers"), expectation);
      assertThat(barriers.path("records").size()).isEqualTo(15);
      assertThat(session.barrierCaptures).isEqualTo(1);
    }

    assertThat(session.closed).isTrue();
  }

  @Test
  void validatesEveryDecodedCloseBarrierAgainstTheExpectedDailyIdentity() {
    final KafkaObservationSession.CloseBarrierExpectation expectation = expectation();
    final List<ObservedRecord> valid = IntStream.range(0, 15)
        .mapToObj(partition -> closeBarrierRecord(partition, "2026-08-27"))
        .toList();

    final KafkaObservationSession.CloseBarrierEvidence evidence =
        KafkaMatchingCommandProbe.validateCloseBarriers(
            "matching.commands", expectation, valid);

    assertThat(evidence.records()).hasSize(15);
    assertThat(evidence.records().get(14).partition()).isEqualTo(14);

    final List<ObservedRecord> wrongDay = IntStream.range(0, 15)
        .mapToObj(
            partition ->
                closeBarrierRecord(partition, partition == 7 ? "2026-08-28" : "2026-08-27"))
        .toList();
    assertThatThrownBy(
            () -> KafkaMatchingCommandProbe.validateCloseBarriers(
                "matching.commands", expectation, wrongDay))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("artifact trading day");

    final List<ObservedRecord> wrongOffset = IntStream.range(0, 15)
        .mapToObj(partition -> closeBarrierRecord(partition, "2026-08-27"))
        .map(record -> record.partition() == 4
            ? new ObservedRecord(record.partition(), record.offset() + 1, record.timestamp(),
                record.key(), record.value())
            : record)
        .toList();
    assertThatThrownBy(
            () -> KafkaMatchingCommandProbe.validateCloseBarriers(
                "matching.commands", expectation, wrongOffset))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frozen offset range");
  }

  private static KafkaObservationSession.CloseBarrierExpectation expectation() {
    return new KafkaObservationSession.CloseBarrierExpectation(
        "2026-08-27-regular",
        "2026-08-27",
        "abcdef",
        "price-time-v1",
        new KafkaObservationSession.TopicEndPositions(
            "matching.commands", StubSession.endOffsets(10L)),
        new KafkaObservationSession.TopicEndPositions(
            "matching.commands", StubSession.endOffsets(11L)));
  }

  private static ObservedRecord closeBarrierRecord(int partition, String tradingDay) {
    final String commandId = "close-" + partition;
    final MatchingCommand command = MatchingCommand.newBuilder()
        .setHeader(
            CommandHeader.newBuilder()
                .setSchemaVersion(1)
                .setCommandId(commandId)
                .setTradingSessionId("2026-08-27-regular")
                .setPartitionId(partition)
                .setArtifactIdentity(
                    ArtifactIdentity.newBuilder()
                        .setTradingDay(tradingDay)
                        .setContentSha256("abcdef"))
                .setRoutingAlgorithmVersion("price-time-v1"))
        .setCloseBarrier(CloseBarrier.getDefaultInstance())
        .build();
    return new ObservedRecord(
        partition, 10L + partition, 100L, commandId, new Bytes(command.toByteArray()));
  }

  private JsonNode post(HttpClient client, URI uri) throws Exception {
    return post(client, uri, "");
  }

  private JsonNode post(HttpClient client, URI uri, String body) throws Exception {
    final HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private static final class StubSession implements KafkaObservationSession {
    private int logEndCaptures;
    private int committedCaptures;
    private int barrierCaptures;
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
    public CloseBarrierEvidence verifyCloseBarriers(CloseBarrierExpectation expectation) {
      barrierCaptures++;
      final List<CloseBarrierRecord> records = IntStream.range(0, 15)
          .mapToObj(
              partition -> new CloseBarrierRecord(partition, 10L + partition, "close-" + partition))
          .toList();
      return new CloseBarrierEvidence("matching.commands", records);
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
