package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TwdNotional;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Rejects foreign Query Pod ownership and ambiguous operating-system process identity. */
class KubernetesQueryPolicyReplacementTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void realFixMatrixFixtureRejectsAmbiguousAccountAndOrderIdentities() {
    final UUID buyer = UUID.randomUUID();
    final UUID seller = UUID.randomUUID();
    final UUID resting = UUID.randomUUID();
    final UUID filledBuy = UUID.randomUUID();
    final UUID filledSell = UUID.randomUUID();
    final UUID ioc = UUID.randomUUID();

    assertThrows(IllegalArgumentException.class,
        () -> new KubernetesQueryPolicyReplacement.Accounts(buyer, buyer));
    assertThrows(IllegalArgumentException.class,
        () -> new KubernetesQueryPolicyReplacement.Orders(resting, filledBuy, filledSell,
            filledSell));
    assertThrows(IllegalArgumentException.class,
        () -> new KubernetesQueryPolicyReplacement.MarketKey("", "1101"));

    final var fixture = new KubernetesQueryPolicyReplacement.Fixture(
        new KubernetesQueryPolicyReplacement.Accounts(buyer, seller),
        new KubernetesQueryPolicyReplacement.Orders(resting, filledBuy, filledSell, ioc),
        new KubernetesQueryPolicyReplacement.MarketKey("XTAI", "1101"));
    assertEquals(buyer, fixture.accounts().buyer());
    assertEquals(ioc, fixture.orders().cancelledIoc());
    assertEquals("1101", fixture.market().symbol());
  }

  @Test
  void accountMoneyOracleUsesAuthorityOutboxPayloadRatherThanQueryProjection()
      throws Exception {
    final String account = UUID.randomUUID().toString();
    final String eventId = UUID.randomUUID().toString();
    final String reservationId = UUID.randomUUID().toString();
    final var event = AccountLifecycleEvent.newBuilder()
        .setMetadata(EventMetadata.newBuilder().setSchemaVersion("v2")
            .setSourceService("account-service").setEventId(eventId))
        .setAccountId(account)
        .setReservationId(reservationId)
        .setReservedNotional(TwdNotional.newBuilder().setUnits(4300))
        .build();
    final var row = json.createObjectNode()
        .put("accountId", account)
        .put("sourceEventId", eventId)
        .put("queryReservedNotional", 4300)
        .put("outboxEventId", eventId)
        .put("outboxTopic", "account.lifecycle")
        .put("outboxMessageKey", account)
        .put("outboxAggregateType", "account_reservation")
        .put("outboxAggregateId", reservationId)
        .put("payloadHex", HexFormat.of().formatHex(event.toByteArray()));
    assertEquals(4300, KubernetesQueryPolicyReplacement.authoritativeReservedNotional(
        row, account));

    row.put("queryReservedNotional", 9999);
    assertThrows(AssertionError.class, () ->
        KubernetesQueryPolicyReplacement.authoritativeReservedNotional(row, account));
    row.put("queryReservedNotional", 4300);
    row.put("outboxMessageKey", UUID.randomUUID().toString());
    assertThrows(AssertionError.class, () ->
        KubernetesQueryPolicyReplacement.authoritativeReservedNotional(row, account));
  }

  @Test
  void policyReplacementRequiresTheExactDeploymentAndReplicaSetChain() throws Exception {
    final var deployment = json.readTree("""
        {"metadata":{"name":"query-service","uid":"deployment-1",
          "labels":{"simplematch.io/verification-run":"run-1"}},
         "spec":{"template":{"metadata":{"annotations":{
           "simplematch.io/policy-generation":"2","simplematch.io/policy-digest":"digest-2"}},
           "spec":{"containers":[{"image":"image:locked"}]}}}}
        """);
    final var replicaSet = json.readTree("""
        {"metadata":{"name":"query-rs","uid":"rs-1",
          "labels":{"simplematch.io/verification-run":"run-1"},
          "ownerReferences":[{"kind":"Deployment","name":"query-service",
            "uid":"deployment-1","controller":true}]},
         "spec":{"selector":{"matchLabels":{"app.kubernetes.io/name":"query-service"}},
           "template":{"metadata":{"annotations":{
             "simplematch.io/policy-generation":"2","simplematch.io/policy-digest":"digest-2"}},
             "spec":{"containers":[{"image":"image:locked"}]}}}}
        """);
    final var pod = json.readTree("""
        {"metadata":{"name":"query-pod","uid":"pod-1",
          "labels":{"simplematch.io/verification-run":"run-1"},
          "annotations":{"simplematch.io/policy-generation":"2",
            "simplematch.io/policy-digest":"digest-2"},
          "ownerReferences":[{"kind":"ReplicaSet","name":"query-rs",
            "uid":"rs-1","controller":true}]}}
        """);
    KubernetesQueryPolicyReplacement.requireControlledPod(pod, replicaSet, deployment, "run-1");
    final ObjectNode wrongParent = replicaSet.deepCopy();
    ((ObjectNode) wrongParent.at("/metadata/ownerReferences/0")).put("uid", "foreign-deployment");
    assertThrows(AssertionError.class, () -> KubernetesQueryPolicyReplacement.requireControlledPod(
        pod, wrongParent, deployment, "run-1"));
    final ObjectNode stalePolicy = pod.deepCopy();
    ((ObjectNode) stalePolicy.at("/metadata/annotations"))
        .put("simplematch.io/policy-digest", "stale");
    assertThrows(AssertionError.class, () -> KubernetesQueryPolicyReplacement.requireControlledPod(
        stalePolicy, replicaSet, deployment, "run-1"));
  }

  @Test
  void runningProcessIdentityReadsStartTimeAfterParenthesizedCommand() {
    final String fields = String.join(" ", Collections.nCopies(18, "0"));
    assertEquals("91991", KubernetesQueryPolicyReplacement.startTime(
        "10 (java task with spaces) S " + fields + " 91991 0 0"));
    assertThrows(IllegalArgumentException.class, () ->
        KubernetesQueryPolicyReplacement.startTime("unparseable process"));
  }
}
