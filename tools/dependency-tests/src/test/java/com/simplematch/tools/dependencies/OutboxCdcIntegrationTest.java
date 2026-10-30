package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.tools.recovery.CdcPublicationVerifier;
import java.time.Instant;
import java.util.EnumMap;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retains exact outbox publication, dependency outages and actual crash-induced redelivery. */
class OutboxCdcIntegrationTest {
  @ParameterizedTest(name = "real outbox recovery with RF{0}")
  @ValueSource(ints = {1, 3})
  @Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
  void exactDurableEventsSurviveConnectorBrokerAndProcessFailures(int replication)
      throws Exception {
    final CdcEnvironment environment = new CdcEnvironment(replication);
    CdcRunEvidence evidence;
    KafkaPublication observations;
    try (environment) {
      environment.start();
      evidence = new CdcRunEvidence(environment);
      final CdcOutbox outbox = new CdcOutbox(environment.database);
      observations = new KafkaPublication(environment.bootstrap());
      try (KafkaPublication kafka = observations) {
        for (CdcOwner owner : CdcOwner.values()) {
          kafka.assertTopology(owner, replication);
          final var before = kafka.baseline(owner);
          outbox.insert(owner, "baseline", 1);
          kafka.verify(outbox.probe(owner, "baseline", null), before);
        }
        final var old = outbox.baseline(CdcOwner.ACCOUNT, "baseline");
        final var transitionOffset = kafka.baseline(CdcOwner.ACCOUNT);
        outbox.insert(CdcOwner.ACCOUNT, "baseline", 2);
        kafka.verify(outbox.probe(CdcOwner.ACCOUNT, "baseline", old), transitionOffset);
        for (CdcOwner owner : CdcOwner.values()) {
          environment.connect.pause(owner);
          final var before = kafka.baseline(owner);
          outbox.insert(owner, "paused", 2);
          final var probe = outbox.probe(owner, "paused", null);
          environment.connect.resume(owner);
          kafka.verify(probe, before);
          CdcPublicationVerifier.sameProbe(probe, outbox.probe(owner, "paused", null));
        }
        final var brokerBaselines = new EnumMap<CdcOwner, JsonNode>(CdcOwner.class);
        final var brokerProbes = new EnumMap<CdcOwner, JsonNode>(CdcOwner.class);
        for (CdcOwner owner : CdcOwner.values()) {
          brokerBaselines.put(owner, kafka.baseline(owner));
        }
        environment.stopBrokers();
        for (CdcOwner owner : CdcOwner.values()) {
          outbox.insert(owner, "broker-unavailable", 3);
          brokerProbes.put(owner, outbox.probe(owner, "broker-unavailable", null));
        }
        environment.startBrokers();
        for (CdcOwner owner : CdcOwner.values()) {
          kafka.verify(brokerProbes.get(owner), brokerBaselines.get(owner));
          CdcPublicationVerifier.sameProbe(
              brokerProbes.get(owner), outbox.probe(owner, "broker-unavailable", null));
        }
        redelivery(environment, outbox, kafka);
      }
    }
    evidence.complete(environment, observations);
  }

  private void redelivery(CdcEnvironment environment, CdcOutbox outbox, KafkaPublication kafka)
      throws Exception {
    final var checkpoints = new EnumMap<CdcOwner, JsonNode>(CdcOwner.class);
    final Instant deadline = Instant.now().plusSeconds(150);
    boolean complete = false;
    while (Instant.now().isBefore(deadline) && !complete) {
      complete = true;
      for (CdcOwner owner : CdcOwner.values()) {
        final var current = environment.connect.offsets(owner);
        checkpoints.put(owner, current);
        complete &= current.isArray() && !current.isEmpty();
      }
      if (!complete) {
        Thread.sleep(250);
      }
    }
    assertTrue(complete, "Connect did not commit a source checkpoint before crash scenario");
    final var probes = new EnumMap<CdcOwner, JsonNode>(CdcOwner.class);
    for (CdcOwner owner : CdcOwner.values()) {
      final var before = kafka.baseline(owner);
      outbox.insert(owner, "uncommitted", 4);
      final var probe = outbox.probe(owner, "uncommitted", null);
      probes.put(owner, probe);
      kafka.verify(probe, before);
    }
    final var crashBaselines = new EnumMap<CdcOwner, JsonNode>(CdcOwner.class);
    for (CdcOwner owner : CdcOwner.values()) {
      assertEquals(
          checkpoints.get(owner),
          environment.connect.offsets(owner),
          "Source offset committed before the controlled crash");
      crashBaselines.put(owner, kafka.baseline(owner));
    }
    environment.crashWorker();
    for (CdcOwner owner : CdcOwner.values()) {
      kafka.verify(probes.get(owner), crashBaselines.get(owner));
      CdcPublicationVerifier.sameProbe(probes.get(owner), outbox.probe(owner, "uncommitted", null));
    }
  }
}
