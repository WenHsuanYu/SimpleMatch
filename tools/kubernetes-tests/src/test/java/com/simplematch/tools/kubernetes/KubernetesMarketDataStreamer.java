package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.tools.riskmatchinge2e.MarketDataSnapshotRecoveryObservationMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Observes one real Recreate replacement through the same public gRPC subscriber. */
final class KubernetesMarketDataStreamer {
  private static final Duration REPLACEMENT_BUDGET = Duration.ofMinutes(3);
  private final KubernetesMarketData market;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesMarketDataStreamer(KubernetesMarketData market) {
    this.market = market;
  }

  void verify(KubernetesMarketData.Instrument instrument) throws Exception {
    final var deployment = market.requireDeployment("marketdata-streamer");
    assertEquals(1, deployment.path("spec").path("replicas").asInt(-1));
    assertEquals("Recreate", deployment.path("spec").path("strategy").path("type").asText());
    final String expectedImage = market.target().replacements()
        .get("simplematch/marketdata-streamer:local");
    assertFalse(expectedImage == null || expectedImage.isBlank());
    final var original = singleReadyPod(expectedImage);
    final String oldUid = original.path("metadata").path("uid").asText();
    final String oldName = original.path("metadata").path("name").asText();
    final String oldNode = original.path("spec").path("nodeName").asText();
    market.save("r17-streamer-before.json", original);

    final Path signals = Files.createDirectory(market.evidence().resolve("r17-signals"));
    final Path firstSubscription = signals.resolve("initial-subscription.ready");
    final Path firstSnapshot = signals.resolve("initial-snapshot.ready");
    final Path disconnected = signals.resolve("disconnected.ready");
    final Path replacementReady = signals.resolve("replacement.ready");
    final Path reconnected = signals.resolve("reconnected.ready");
    final Path secondSnapshot = signals.resolve("resubscribed-snapshot.ready");
    final Path recovery = market.evidence().resolve("r17-client-recovery.json");

    try (var firstForward = market.client().forward("service/marketdata-streamer", 50053,
        market.evidence().resolve("r17-streamer-initial-forward.log"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final int port = firstForward.port();
      final Future<?> observed = executor.submit(() -> {
        MarketDataSnapshotRecoveryObservationMain.main(new String[] {
            "--host", "127.0.0.1", "--port", Integer.toString(port),
            "--venue-mic", instrument.venueMic(), "--symbol", instrument.symbol(),
            "--timeout-seconds", "300", "--evidence", recovery.toString(),
            "--initial-subscription-ready", firstSubscription.toString(),
            "--initial-snapshot-ready", firstSnapshot.toString(),
            "--disconnected-ready", disconnected.toString(),
            "--replacement-ready", replacementReady.toString(),
            "--reconnected-ready", reconnected.toString(),
            "--resubscribed-snapshot-ready", secondSnapshot.toString()
        });
        return null;
      });
      try {
        await(firstSubscription, observed);
        market.replay("r17-initial");
        await(firstSnapshot, observed);
        assertEquals(oldUid, singleReadyPod(expectedImage).path("metadata").path("uid").asText(),
            "streamer owner must remain unchanged before the injected fault");
        assertFalse(Files.exists(disconnected),
            "public stream disconnected before the injected Recreate replacement");
        market.client().run("", "rollout", "restart", "deployment/marketdata-streamer");
        awaitOldGone(oldUid);
        final long oldGoneNanos = System.nanoTime();
        final long oldGoneEpochMs = System.currentTimeMillis();
        final var replacement = awaitReplacement(oldUid, expectedImage);
        final long newReadyNanos = System.nanoTime();
        final long newReadyEpochMs = System.currentTimeMillis();
        assertTrue(newReadyNanos > oldGoneNanos, "replacement must follow old owner removal");
        market.save("r17-streamer-after.json", replacement);
        firstForward.close();
        try (var nextForward = market.client().forward("service/marketdata-streamer", 50053,
            market.evidence().resolve("r17-streamer-replacement-forward.log"), port,
            Duration.ofSeconds(45))) {
          assertEquals(port, nextForward.port(), "public client must keep its local endpoint");
          Files.writeString(replacementReady, "READY\n");
          await(disconnected, observed);
          await(reconnected, observed);
          market.replay("r17-resubscribed");
          await(secondSnapshot, observed);
          observed.get(REPLACEMENT_BUDGET.toSeconds(), TimeUnit.SECONDS);
        }
        final var client = json.readTree(recovery.toFile());
        assertTrue(client.path("connections").size() >= 2,
            "public client must establish two independent subscriptions");
        assertTrue(client.path("connections").get(0).path("snapshotObserved").asBoolean());
        assertTrue(client.path("connections").get(1).path("snapshotObserved").asBoolean());
        assertEquals(2, client.path("snapshots").size());
        for (var snapshot : client.path("snapshots")) {
          assertTrue(snapshot.path("completeSnapshot").asBoolean());
          assertEquals(instrument.venueMic(), snapshot.path("venueMic").asText());
          assertEquals(instrument.symbol(), snapshot.path("symbol").asText());
        }
        market.save("r17-owner.json", Map.of(
            "oldPodUid", oldUid, "oldPod", oldName, "oldNode", oldNode,
            "newPodUid", replacement.path("metadata").path("uid").asText(),
            "newNode", replacement.path("spec").path("nodeName").asText(),
            "oldPodGoneEpochMs", oldGoneEpochMs, "newPodReadyEpochMs", newReadyEpochMs,
            "oldPodAbsentBeforeNewReadyObserved", true));
      } finally {
        observed.cancel(true);
      }
    }
  }

  private JsonNode singleReadyPod(String expectedImage) throws Exception {
    final var pods = pods();
    assertEquals(1, pods.size(), "public streamer must have one run-owned owner");
    final var pod = pods.get(0);
    assertTrue(ready(pod, expectedImage));
    return pod;
  }

  private JsonNode awaitReplacement(String oldUid, String expectedImage) throws Exception {
    final var budget = OperationBudget.start(REPLACEMENT_BUDGET);
    while (true) {
      budget.remaining();
      final var pods = pods();
      budget.remaining();
      if (pods.size() == 1 && !oldUid.equals(pods.get(0).path("metadata").path("uid").asText())
          && ready(pods.get(0), expectedImage)) {
        return pods.get(0);
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
    }
  }

  private void awaitOldGone(String oldUid) throws Exception {
    final var budget = OperationBudget.start(REPLACEMENT_BUDGET);
    while (true) {
      budget.remaining();
      final var pods = pods();
      budget.remaining();
      boolean oldPresent = false;
      for (var pod : pods) {
        oldPresent |= oldUid.equals(pod.path("metadata").path("uid").asText());
      }
      if (!oldPresent) {
        market.save("r17-old-pod-absent.json", pods);
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
    }
  }

  private JsonNode pods() throws Exception {
    return market.client().read("get", "pods", "-l", "app.kubernetes.io/name=marketdata-streamer,"
        + OwnedNamespace.OWNER_LABEL + "=" + market.target().run().runId(), "-o", "json")
        .required("items");
  }

  static boolean ready(JsonNode pod, String image) {
    if (pod.path("metadata").path("deletionTimestamp").isTextual()
        || pod.path("metadata").path("uid").asText().isBlank()
        || pod.path("spec").path("nodeName").asText().isBlank()
        || !"Running".equals(pod.path("status").path("phase").asText())) {
      return false;
    }
    boolean conditionReady = false;
    for (var condition : pod.path("status").path("conditions")) {
      conditionReady |= "Ready".equals(condition.path("type").asText())
          && "True".equals(condition.path("status").asText());
    }
    boolean containerReady = false;
    boolean imageMatches = false;
    for (var container : pod.path("status").path("containerStatuses")) {
      containerReady |= "marketdata-streamer".equals(container.path("name").asText())
          && container.path("ready").asBoolean();
    }
    for (var container : pod.path("spec").path("containers")) {
      imageMatches |= "marketdata-streamer".equals(container.path("name").asText())
          && image.equals(container.path("image").asText());
    }
    return conditionReady && containerReady && imageMatches;
  }

  private static void await(Path signal, Future<?> observer) throws Exception {
    final var budget = OperationBudget.start(Duration.ofMinutes(3));
    while (true) {
      budget.remaining();
      if (Files.isRegularFile(signal)) {
        return;
      }
      if (observer.isDone()) {
        observer.get();
        throw new IllegalStateException("public streamer observer finished before signal: "
            + signal.getFileName());
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(250)));
    }
  }
}
