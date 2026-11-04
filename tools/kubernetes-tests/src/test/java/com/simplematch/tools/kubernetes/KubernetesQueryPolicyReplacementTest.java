package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Rejects foreign Query Pod ownership and ambiguous operating-system process identity. */
class KubernetesQueryPolicyReplacementTest {
  private final ObjectMapper json = new ObjectMapper();

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
