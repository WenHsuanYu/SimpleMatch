package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("kubernetes-live")
class RiskCommandKubernetesTest {
  @Test
  void acceptedAdmissionAndKafkaRecordPreserveTheDurableOutboxBytesAndArtifactRoute()
      throws Exception {
    final var environment = new RiskCommandEnvironment();
    VerificationRun.verify(environment.request(), environment,
        run -> assertFacts(environment.submitAndObserve(run)));
  }

  static void assertFacts(RiskCommandEnvironment.Facts facts) {
    final var selected = facts.selected();
    final var admission = facts.admission();
    final var outbox = facts.outbox();
    assertAll(
        () -> assertEquals(facts.expected().path("runId"), selected.path("runId")),
        () -> assertEquals(facts.expected().path("tradingDay"), selected.path("tradingDay")),
        () -> assertEquals(facts.expected().path("artifactContentSha256"),
            selected.path("artifactContentSha256")),
        () -> assertEquals("PASS", facts.verifier().path("status").asText()),
        () -> assertEquals("ACCEPTED", facts.outcome().path("terminalStatus").asText()),
        () -> assertEquals("ACCEPTED", admission.path("state").asText()),
        () -> assertEquals(facts.request().path("commandId"), admission.path("commandId")),
        () -> assertEquals(facts.request().path("orderId"), admission.path("orderId")),
        () -> assertEquals(facts.expected().path("accountId").asText(), admission.path("accountId").asText()),
        () -> assertEquals(selected.path("expectedPartition"), admission.path("routingPartition")),
        () -> assertEquals(selected.path("tradingDay"), admission.path("artifactTradingDay")),
        () -> assertEquals(selected.path("artifactContentSha256"), admission.path("artifactContentSha256")),
        () -> assertEquals(selected.path("routingAlgorithmVersion"), admission.path("routingAlgorithmVersion")),
        () -> assertEquals("matching.commands", outbox.path("topic").asText()),
        () -> assertEquals(facts.request().path("commandId"), outbox.path("messageKey")),
        () -> assertEquals(selected.path("expectedPartition"), outbox.path("partition")),
        () -> assertEquals("simplematch.matching.runtime.v1.MatchingCommand", outbox.path("payloadType").asText()),
        () -> assertFalse(outbox.path("payloadBase64").asText().isEmpty()),
        () -> assertEquals(outbox.path("payloadBase64"), facts.record().path("payloadBase64")));
  }
}
