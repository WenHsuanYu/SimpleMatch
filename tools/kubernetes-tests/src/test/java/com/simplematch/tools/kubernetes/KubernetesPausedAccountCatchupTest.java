package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Rejects false catch-up from a broker position lacking the exact durable Account event. */
class KubernetesPausedAccountCatchupTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void requiresReceiptInboxCheckpointAndAccountViewToAgree() throws Exception {
    final var receipt = new KubernetesPausedAccountCatchup.Receipt(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0, 5,
        "a".repeat(64), 3000);
    final var checkpoints = json.readTree("""
        [{"partition":0,"offset":5,"state":"READY"}]
        """);
    final var observed = json.valueToTree(Map.of(
        "inbox", List.of(Map.of("eventId", receipt.eventId(), "topic", "account.lifecycle",
            "partition", 0, "offset", 5, "payloadSha256", receipt.payloadSha256())),
        "accounts", List.of(Map.of("accountId", receipt.accountId(),
            "eventId", receipt.eventId(), "state", "ACCOUNT_LIFECYCLE_STATE_RESERVED",
            "notional", 3000))));
    assertTrue(KubernetesPausedAccountCatchup.caughtUp(
        checkpoints, observed, Map.of(0, 6L), List.of(receipt)));
    assertFalse(KubernetesPausedAccountCatchup.caughtUp(
        checkpoints, observed, Map.of(0, 7L), List.of(receipt)));
    final var ahead = json.readTree("""
        [{"partition":0,"offset":6,"state":"READY"}]
        """);
    assertFalse(KubernetesPausedAccountCatchup.caughtUp(
        ahead, observed, Map.of(0, 6L), List.of(receipt)));
    final var wrongEvent = observed.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) wrongEvent.at("/inbox/0"))
        .put("payloadSha256", "b".repeat(64));
    assertFalse(KubernetesPausedAccountCatchup.caughtUp(
        checkpoints, wrongEvent, Map.of(0, 6L), List.of(receipt)));
    final var wrongAccount = observed.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) wrongAccount.at("/accounts/0"))
        .put("eventId", UUID.randomUUID().toString());
    assertFalse(KubernetesPausedAccountCatchup.caughtUp(
        checkpoints, wrongAccount, Map.of(0, 6L), List.of(receipt)));
  }

  @Test
  void publisherRequiresCanonicalDistinctAccountEventsAndBuildsConsumableProtobuf()
      throws Exception {
    final String account = UUID.randomUUID().toString();
    final String event = UUID.randomUUID().toString();
    final var fixture = json.valueToTree(Map.of("seeds", List.of(
        Map.of("accountId", account, "eventId", event, "partition", 0,
            "reservedNotionalUnits", 3000),
        Map.of("accountId", UUID.randomUUID().toString(),
            "eventId", UUID.randomUUID().toString(), "partition", 1,
            "reservedNotionalUnits", 4000))));
    final var seeds = KubernetesPausedAccountPublisher.seeds(fixture);
    assertEquals(2, seeds.size());
    final String deployedFixture = json.writeValueAsString(Map.of("seeds", seeds));
    assertEquals(seeds, KubernetesPausedAccountPublisher.seeds(json.readTree(deployedFixture)));
    final var message = AccountLifecycleEvent.parseFrom(
        KubernetesPausedAccountPublisher.payload(seeds.getFirst()));
    assertEquals(account, message.getAccountId());
    assertEquals(event, message.getMetadata().getEventId());
    assertEquals(3000, message.getReservedNotional().getUnits());
    ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.at("/seeds/1"))
        .put("accountId", account);
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesPausedAccountPublisher.seeds(fixture));
  }
}
