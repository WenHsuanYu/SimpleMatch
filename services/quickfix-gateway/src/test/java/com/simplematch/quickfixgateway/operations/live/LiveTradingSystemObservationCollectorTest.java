package com.simplematch.quickfixgateway.operations.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.quickfixgateway.config.GatewayLiveObservationProperties;
import com.simplematch.quickfixgateway.operations.CriticalConsumer;
import com.simplematch.quickfixgateway.operations.TradingReadiness;
import com.simplematch.quickfixgateway.operations.TradingSystemObservation;
import com.simplematch.quickfixgateway.operations.TradingSystemStatusEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveTradingSystemObservationCollectorTest {
  private static final Instant NOW = Instant.parse("2026-10-05T01:00:00Z");
  private static final String CHECKSUM = "a".repeat(64);
  private static final String IMAGE = "sha256:" + "b".repeat(64);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void completeHealthySourcesProduceAnOpenEligibleObservation() {
    final Fixture fixture = fixture(NOW);

    final TradingSystemObservation observation = fixture.collector().collect();

    assertThat(evaluate(observation, NOW).readiness())
        .isEqualTo(TradingReadiness.OPEN_ELIGIBLE);
    assertThat(observation.matchingFleet().partitions()).hasSize(15);
    assertThat(observation.criticalConsumers()).hasSize(3);
  }

  @Test
  void staleSourceFactsRequireAPause() {
    final Fixture fixture = fixture(NOW.minusSeconds(6));

    assertThat(evaluate(fixture.collector().collect(), NOW).readiness())
        .isEqualTo(TradingReadiness.PAUSE_REQUIRED);
  }

  @Test
  void missingPartitionDocumentFailsClosedWithoutAPartialObservation() {
    final Fixture fixture = fixture(NOW);
    fixture.documents().remove("matching-9");

    assertThatThrownBy(() -> fixture.collector().collect())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing document");
  }

  @Test
  void duplicateMatchingOwnerInterruptsTheMarket() {
    final Fixture fixture = fixture(NOW);
    fixture.documents().put("matching-1", matchingStatus(1, "owner-0", true, NOW));

    assertThat(evaluate(fixture.collector().collect(), NOW).readiness())
        .isEqualTo(TradingReadiness.INTERRUPT_REQUIRED);
  }

  @Test
  void incompleteMatchingRecoveryRequiresAPause() {
    final Fixture fixture = fixture(NOW);
    fixture.documents().put("matching-4", matchingStatus(4, "owner-4", false, NOW));

    assertThat(evaluate(fixture.collector().collect(), NOW).readiness())
        .isEqualTo(TradingReadiness.PAUSE_REQUIRED);
  }

  @Test
  void malformedRiskIdentityFailsClosed() {
    final Fixture fixture = fixture(NOW);
    fixture.documents().put("risk", mapper.createObjectNode().put("observedAt", NOW.toString()));

    assertThatThrownBy(() -> fixture.collector().collect())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity");
  }

  @Test
  void consumerQuarantineInterruptsWithoutAConsumerTradingIdentity() {
    final Fixture fixture = fixture(NOW);
    ((ObjectNode) fixture.documents().get("account")).put("quarantined", true);

    final var status = evaluate(fixture.collector().collect(), NOW);

    assertThat(status.readiness()).isEqualTo(TradingReadiness.INTERRUPT_REQUIRED);
    assertThat(status.reasons()).contains("CRITICAL_CONSUMER_ACCOUNT_QUARANTINED");
  }

  @Test
  void missingCriticalConsumerProgressFailsClosed() {
    final Fixture fixture = fixture(NOW);
    fixture.eventEndOffsets().put(0, 1L);
    fixture.commits().put(CriticalConsumer.ACCOUNT, Map.of());

    assertThatThrownBy(() -> fixture.collector().collect())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("consumer progress is incomplete");
  }

  @Test
  void restartedConsumerUsesDurableKafkaCommitWhenLocalProgressIsEmpty() {
    final Fixture fixture = fixture(NOW);
    final ObjectNode account = (ObjectNode) fixture.documents().get("account");
    account.set("committedOffsets", mapper.createObjectNode());
    fixture.eventEndOffsets().put(0, 1L);
    final Map<Integer, Long> committed = new HashMap<>(zeroOffsets());
    committed.put(0, 1L);
    fixture.commits().put(CriticalConsumer.ACCOUNT, Map.copyOf(committed));

    final TradingSystemObservation observation = fixture.collector().collect();
    assertThat(observation.criticalConsumers().stream()
            .filter(status -> status.component() == CriticalConsumer.ACCOUNT)
            .findFirst().orElseThrow().partitionProgress().getFirst().committedOffset())
        .isEqualTo(1L);
  }

  private Fixture fixture(Instant observedAt) {
    final Map<String, JsonNode> documents = new HashMap<>();
    documents.put("risk", riskStatus(observedAt));
    documents.put("account", consumerStatus(observedAt));
    documents.put("persistence", consumerStatus(observedAt));
    documents.put("quickfix", consumerStatus(observedAt));
    for (int partition = 0; partition < 15; partition++) {
      documents.put(
          "matching-" + partition,
          matchingStatus(partition, "owner-" + partition, true, observedAt));
    }
    final StatusDocumentClient documentClient =
        endpoint -> {
          final JsonNode document = documents.get(endpoint);
          if (document == null) {
            throw new IllegalStateException("missing document: " + endpoint);
          }
          return document;
        };
    final GatewayLiveObservationProperties.Endpoints endpoints =
        new GatewayLiveObservationProperties.Endpoints(
            "risk", "matching-%d", "account", "persistence");
    final GatewayLiveStatusClient statusClient =
        new GatewayLiveStatusClient(
            documentClient,
            endpoints,
            () ->
                new CriticalConsumerOperationalStatus(
                    false, zeroOffsets(), Map.of(), observedAt),
            Runnable::run);
    final Map<Integer, Long> offsets = zeroOffsets();
    final Map<Integer, Long> eventEndOffsets = new HashMap<>(offsets);
    final EnumMap<CriticalConsumer, Map<Integer, Long>> commits =
        new EnumMap<>(CriticalConsumer.class);
    for (CriticalConsumer consumer : CriticalConsumer.values()) {
      commits.put(consumer, offsets);
    }
    return new Fixture(
        documents,
        eventEndOffsets,
        commits,
        new LiveTradingSystemObservationCollector(
            statusClient,
            () -> new KafkaAdmissionSnapshot(
                15, 15, offsets, eventEndOffsets, commits, observedAt),
            Runnable::run));
  }

  private ObjectNode riskStatus(Instant observedAt) {
    final ObjectNode identity = mapper.createObjectNode();
    identity.put("tradingSessionId", "2026-10-05-regular");
    identity.put("artifactId", "market-reference-2026-10-05");
    identity.put("artifactContentSha256", CHECKSUM);
    identity.put("commandSchemaVersion", 1);
    identity.put("eventSchemaVersion", 1);
    identity.put("matchingAlgorithmVersion", "stable-least-loaded-v1");
    identity.put("matchingImageIdentity", IMAGE);
    final ObjectNode root = mapper.createObjectNode();
    root.set("identity", identity);
    root.put("observedAt", observedAt.toString());
    return root;
  }

  private ObjectNode matchingStatus(
      int partition, String owner, boolean recovered, Instant observedAt) {
    final ObjectNode artifact = mapper.createObjectNode();
    artifact.put("id", "market-reference-2026-10-05");
    artifact.put("content_sha256", CHECKSUM);
    final ObjectNode identity = mapper.createObjectNode();
    identity.put("trading_session_id", "2026-10-05-regular");
    identity.set("artifact", artifact);
    identity.put("command_schema_version", 1);
    identity.put("event_schema_version", 1);
    identity.put("matching_algorithm_version", "stable-least-loaded-v1");
    identity.put("matching_image_identity", IMAGE);
    final ObjectNode admission = mapper.createObjectNode();
    admission.put("partition_id", partition);
    admission.put("owner_id", owner);
    admission.set("identity", identity);
    admission.put("ownership_permitted", true);
    admission.put("recovery_complete", recovered);
    final ObjectNode root = mapper.createObjectNode();
    root.put("schema_version", 1);
    root.put("updated_at_epoch_ms", observedAt.toEpochMilli());
    root.put("runtime_state", recovered ? "READY" : "RUNNING");
    root.put("partition_state", recovered ? "OPEN" : "AWAITING_OPEN");
    root.put("next_commit_offset", 0);
    root.set("admission", admission);
    return root;
  }

  private ObjectNode consumerStatus(Instant observedAt) {
    final ObjectNode root = mapper.createObjectNode();
    root.put("quarantined", false);
    root.set("committedOffsets", mapper.valueToTree(zeroOffsets()));
    root.set("oldestUnprocessedAgeMillis", mapper.createObjectNode());
    root.put("observedAt", observedAt.toString());
    return root;
  }

  private static Map<Integer, Long> zeroOffsets() {
    final Map<Integer, Long> offsets = new HashMap<>();
    for (int partition = 0; partition < 15; partition++) {
      offsets.put(partition, 0L);
    }
    return Map.copyOf(offsets);
  }

  private static com.simplematch.quickfixgateway.operations.TradingSystemStatus evaluate(
      TradingSystemObservation observation, Instant now) {
    return new TradingSystemStatusEvaluator(
            15, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2))
        .evaluate(observation, now);
  }

  private record Fixture(
      Map<String, JsonNode> documents,
      Map<Integer, Long> eventEndOffsets,
      EnumMap<CriticalConsumer, Map<Integer, Long>> commits,
      LiveTradingSystemObservationCollector collector) {}
}
