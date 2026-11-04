package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Replaces one run-owned Query policy only after the previous generation is stopped. */
final class KubernetesQueryPolicyReplacement {
  private static final String WORKLOAD = "query-service";
  private static final String SECRET = "query-service-secrets";
  private static final Duration STOP_BUDGET = Duration.ofSeconds(20);
  private static final Duration START_BUDGET = Duration.ofMinutes(3);
  private static final java.util.regex.Pattern STAT_FIELDS = java.util.regex.Pattern.compile("\\S+");

  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesScenarioTarget target;
  private final ObjectMapper json = new ObjectMapper();
  private final Path evidence;
  private final String image;
  private final KubernetesOwnership ownership;
  private final BoundedCommand docker;

  KubernetesQueryPolicyReplacement(KubernetesBusinessEnvironment.PublicOrder context)
      throws Exception {
    this.context = context;
    target = context.target();
    evidence = Files.createDirectory(target.run().evidence().resolve("query-policy"));
    image = target.replacements().get("simplematch/" + WORKLOAD + ":local");
    assertFalse(image == null || image.isBlank(), "run has no locked Query image");
    ownership = KubernetesOwnership.read(target.run().evidence().resolve("ownership.json"),
        context.settings().coordination());
    assertEquals(target.run().runId(), ownership.runId());
    assertEquals(target.namespace(), ownership.namespace());
    docker = ownership.docker().context().commands(context.settings().root());
  }

  void verify(UUID account, UUID order) throws Exception {
    final var secret = currentSecret();
    final String dsn = new String(Base64.getDecoder().decode(
        secret.path("data").path("postgres_dsn").asText()), StandardCharsets.UTF_8);
    assertFalse(dsn.isBlank(), "the run's Query PostgreSQL connection is missing");
    final var original = owners();
    assertEquals(2, original.size(), "Query policy requires two original replicas");
    final var previous = new ArrayList<String>();
    previous.add(context.queryCredentials().readerToken());
    previous.add(context.queryCredentials().operationsToken());
    final String deploymentUid = deployment().path("metadata").path("uid").asText();
    String expectedSecretUid = secret.path("metadata").path("uid").asText();
    String servingReader = context.queryCredentials().readerToken();

    for (int version : List.of(2, 3)) {
      final var generation = context.queryCredentials().replacement(version, dsn);
      expectedSecretUid = replace(generation, original, deploymentUid, expectedSecretUid,
          servingReader, order);
      final var retiredTokens = List.copyOf(previous);
      previous.add(generation.readerToken());
      previous.add(generation.operationsToken());
      previous.add(generation.retainedReaderToken());
      final var current = owners();
      verifyServing(version, current, generation, retiredTokens, account, order);
      // A new generation must revoke every token from earlier generations.
      assertEquals(2, current.size());
      servingReader = generation.retainedReaderToken();
    }
  }

  private String replace(KubernetesQueryCredentials.Generation generation, List<Owner> baseline,
      String deploymentUid, String expectedSecretUid, String servingReader, UUID order)
      throws Exception {
    requireNamespace();
    final var originalDeployment = deployment();
    assertEquals(deploymentUid, originalDeployment.path("metadata").path("uid").asText());
    assertEquals(2, originalDeployment.path("spec").path("replicas").asInt(-1));
    assertEquals(2, originalDeployment.path("status").path("readyReplicas").asInt(-1));
    final var old = owners();
    assertEquals(2, old.size());
    if (generation.version() == 2) {
      assertEquals(baseline, old, "Query baseline changed before policy replacement");
    }
    Files.write(evidence.resolve("v" + generation.version() + "-old-owners.json"),
        json.writeValueAsBytes(old));
    final var fence = new KubernetesQueryStopFence(context);
    final var tasks = fence.capture();
    assertEquals(old.stream().map(owner ->
            owner.uid() + "/" + owner.node() + "/" + owner.containerId())
            .collect(java.util.stream.Collectors.toSet()),
        tasks.stream().map(task ->
            task.podUid() + "/" + task.node() + "/" + task.containerId())
            .collect(java.util.stream.Collectors.toSet()),
        "physical Query tasks differ from the validated Pod ownership chain");
    try (var transport = KubernetesQueryTransport.open(target, docker, ownership.docker().context(),
        evidence, generation.version(), servingReader, order.toString(), old)) {
      requireNamespace();
      target.client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=0");
      awaitOldGenerationStopped(old, tasks, fence);
      transport.verifyBlocked();
    }

    // The old policy is never restored after this point, including on a failed rollout.
    // Both replacement generations must demonstrate live stopped-window
    // Account input and independent durable Query catch-up.
    final var catchup = new KubernetesPausedAccountCatchup(
        context, image, evidence, generation.version());
    catchup.publishWhileStopped(fence, tasks);
    requireNamespace();
    final var previousSecret = currentSecret();
    final String oldSecretUid = previousSecret.path("metadata").path("uid").asText();
    assertEquals(expectedSecretUid, oldSecretUid,
        "Query Secret owner changed before the controlled policy replacement");
    target.client().run("", "delete", "secret", SECRET, "--wait=true");
    target.create(KubernetesDocuments.data("Secret", SECRET, generation.secret()));
    final var nextSecret = currentSecret();
    assertNotEquals(oldSecretUid, nextSecret.path("metadata").path("uid").asText(),
        "the Query policy Secret was not replaced");
    final String expectedPolicy = generation.secret().get("query-service.properties");
    final String actualPolicy = new String(Base64.getDecoder().decode(
        nextSecret.path("data").path("query-service.properties").asText()),
        StandardCharsets.UTF_8);
    assertTrue(MessageDigest.isEqual(expectedPolicy.getBytes(StandardCharsets.UTF_8),
        actualPolicy.getBytes(StandardCharsets.UTF_8)),
        "run-owned Query Secret does not match the selected policy generation");
    final String policyDigest = sha256(expectedPolicy);
    final String patch = json.writeValueAsString(Map.of("spec", Map.of("template", Map.of(
        "metadata", Map.of("annotations", Map.of(
            "simplematch.io/policy-generation", Integer.toString(generation.version()),
            "simplematch.io/policy-digest", policyDigest))))));
    target.client().run("", "patch", "deployment", WORKLOAD, "--type=merge", "--patch", patch);
    final OperationBudget recoveryBudget = OperationBudget.start(Duration.ofMinutes(2));
    target.client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=2");
    target.client().execute("", recoveryBudget.remaining(Duration.ofSeconds(115)),
        "rollout", "status", "deployment/" + WORKLOAD, "--timeout=110s").checked();
    awaitNewGeneration(generation.version(), old, deploymentUid, policyDigest, recoveryBudget);
    catchup.verifyCatchup(recoveryBudget);
    Files.write(evidence.resolve("v" + generation.version() + "-activation.json"),
        json.writeValueAsBytes(Map.of("policyVersion", generation.version(),
            "policyDigest", policyDigest, "secretUid",
            nextSecret.path("metadata").path("uid").asText(), "oldPods", old, "newPods", owners())));
    return nextSecret.path("metadata").path("uid").asText();
  }

  private void awaitOldGenerationStopped(List<Owner> old,
      List<KubernetesQueryStopFence.Task> tasks, KubernetesQueryStopFence fence) throws Exception {
    fence.awaitStopped(tasks, STOP_BUDGET);
    Files.write(evidence.resolve("v" + nextVersionFromOld(old) + "-fence.json"),
        json.writeValueAsBytes(Map.of("runId", target.run().runId(),
            "oldPods", old, "observedTasks", tasks,
            "observedZeroPods", true, "oldTasksStopped", true)));
  }

  private static int nextVersionFromOld(List<Owner> old) {
    return old.getFirst().policyVersion() + 1;
  }

  static String startTime(String stat) {
    final int end = stat.lastIndexOf(')');
    if (end < 0) {
      throw new IllegalArgumentException("process observation has no command terminator");
    }
    final var fields = STAT_FIELDS.matcher(stat.substring(end + 1));
    int index = 0;
    while (fields.find()) {
      if (index++ == 19) {
        final String start = fields.group();
        if (start.matches("[0-9]+")) {
          return start;
        }
        break;
      }
    }
    throw new IllegalArgumentException("process observation has no start identity");
  }

  private void awaitNewGeneration(int version, List<Owner> old, String deploymentUid,
      String policyDigest, OperationBudget recoveryBudget) throws Exception {
    final var budget = recoveryBudget == null
        ? OperationBudget.start(START_BUDGET) : recoveryBudget;
    while (true) {
      budget.remaining();
      final var current = deployment();
      final var pods = queryPods();
      if (current.path("spec").path("replicas").asInt(-1) == 2
          && current.path("status").path("readyReplicas").asInt() == 2
          && current.path("status").path("observedGeneration").asLong(-1)
              >= current.path("metadata").path("generation").asLong(Long.MAX_VALUE)
          && pods.size() == 2) {
        assertEquals(deploymentUid, current.path("metadata").path("uid").asText());
        assertEquals(Integer.toString(version), current.at(
            "/spec/template/metadata/annotations/simplematch.io~1policy-generation").asText());
        assertEquals(policyDigest, current.at(
            "/spec/template/metadata/annotations/simplematch.io~1policy-digest").asText());
        final var observed = owners();
        for (var pod : observed) {
          assertTrue(old.stream().noneMatch(previous -> previous.uid().equals(pod.uid())),
              "retired Query Pod UID reappeared after policy activation");
          assertEquals(version, pod.policyVersion());
        }
        assertNotEquals(observed.get(0).node(), observed.get(1).node(),
            "Query replacement replicas must be on distinct workers");
        context.workloadImages().verifySelected(target.client(), target.run(),
            "query-policy-v" + version, WORKLOAD, 2);
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(300)));
    }
  }

  private void verifyServing(int version, List<Owner> current,
      KubernetesQueryCredentials.Generation generation, List<String> retiredTokens,
      UUID account, UUID order) throws Exception {
    final var observations = new ArrayList<Map<String, Object>>();
    for (var pod : current) {
      try (var forward = target.client().forward("pod/" + pod.name(), 8086,
          evidence.resolve("v" + version + "-" + pod.name() + "-forward.log"));
          var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
        final var authenticated = request(http, forward.port(), "/api/v1/freshness",
            generation.operationsToken());
        assertEquals(200, authenticated.statusCode(),
            "the new operations Principal is not serving");
        assertEquals(Integer.toString(version), authenticated.headers().firstValue(
            "X-SimpleMatch-Query-Policy-Version").orElse(""),
            "a Query Pod is serving the wrong policy generation");
        final String path = "/api/v1/orders/" + order;
        final int readerStatus = request(http, forward.port(), path,
            generation.readerToken()).statusCode();
        assertEquals(version == 2 ? 404 : 401, readerStatus,
            "revoked account grant or disabled Principal remained authorized");
        assertEquals(200, request(http, forward.port(), path,
            generation.retainedReaderToken()).statusCode(),
            "the permitted Account Order disappeared or a retained grant stopped working");
        for (String old : retiredTokens) {
          assertEquals(401, request(http, forward.port(), path, old).statusCode(),
              "a retired credential remained active after Query policy replacement");
        }
        observations.add(Map.of("pod", pod.name(), "uid", pod.uid(),
            "policyVersion", version, "account", account.toString(),
            "readerStatus", readerStatus, "retainedReaderStatus", 200,
            "operationsStatus", authenticated.statusCode(),
            "retiredTokenRejections", retiredTokens.size()));
      }
    }
    Files.write(evidence.resolve("v" + version + "-serving.json"),
        json.writeValueAsBytes(observations));
    assertEquals(current, owners(), "Query owner changed while observing effective revocation");
  }

  private static HttpResponse<String> request(HttpClient http, int port, String path, String token)
      throws Exception {
    final var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(15)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode deployment() throws Exception {
    final var value = target.client().read("get", "deployment", WORKLOAD, "-o", "json");
    assertEquals(target.run().runId(), value.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertFalse(value.path("metadata").path("uid").asText().isBlank());
    assertEquals(image, value.at("/spec/template/spec/containers/0/image").asText());
    return value;
  }

  private JsonNode currentSecret() throws Exception {
    final var value = target.client().read("get", "secret", SECRET, "-o", "json");
    assertEquals(target.run().runId(), value.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertTrue(value.path("immutable").asBoolean(), "Query generation Secret must be immutable");
    assertFalse(value.path("metadata").path("uid").asText().isBlank());
    return value;
  }

  private List<Owner> owners() throws Exception {
    final var expectedDeployment = deployment();
    final var selected = new ArrayList<Owner>();
    for (var pod : queryPods()) {
      assertEquals(target.run().runId(), pod.path("metadata").path("labels")
          .path(OwnedNamespace.OWNER_LABEL).asText());
      final String replicaSetName = controller(pod.path("metadata").path("ownerReferences"),
          "ReplicaSet").path("name").asText();
      assertFalse(replicaSetName.isBlank(), "Query Pod has no controlling ReplicaSet name");
      final var replicaSet = target.client().read("get", "replicaset", replicaSetName, "-o", "json");
      requireControlledPod(pod, replicaSet, expectedDeployment, target.run().runId());
      final var ready = KubernetesQueryHealth.requireOwner(pod, image);
      final String containerId = pod.at("/status/containerStatuses/0/containerID").asText();
      assertTrue(containerId.matches("containerd://[0-9a-f]{64}"),
          "Query Pod has no exact containerd task identity");
      selected.add(new Owner(ready.name(), ready.uid(), ready.node(),
          containerId.substring("containerd://".length()),
          pod.path("status").path("podIP").asText(), versionFromPod(pod)));
    }
    selected.sort(Comparator.comparing(Owner::name));
    return List.copyOf(selected);
  }

  private int versionFromPod(JsonNode pod) {
    final String value = pod.at(
        "/metadata/annotations/simplematch.io~1policy-generation").asText("1");
    return Integer.parseInt(value);
  }

  static void requireControlledPod(JsonNode pod, JsonNode replicaSet, JsonNode deployment,
      String runId) {
    assertEquals(runId, pod.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertEquals(runId, replicaSet.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertEquals(runId, deployment.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    final var podOwner = controller(pod.path("metadata").path("ownerReferences"), "ReplicaSet");
    assertEquals(replicaSet.path("metadata").path("uid").asText(), podOwner.path("uid").asText());
    assertEquals(replicaSet.path("metadata").path("name").asText(), podOwner.path("name").asText());
    final var rsOwner = controller(replicaSet.path("metadata").path("ownerReferences"),
        "Deployment");
    assertEquals(deployment.path("metadata").path("uid").asText(), rsOwner.path("uid").asText(),
        "Query ReplicaSet is not controlled by the selected Deployment");
    assertEquals(WORKLOAD, rsOwner.path("name").asText());
    assertEquals(WORKLOAD, replicaSet.at(
        "/spec/selector/matchLabels/app.kubernetes.io~1name").asText());
    final String expectedImage = deployment.at("/spec/template/spec/containers/0/image").asText();
    assertFalse(expectedImage.isBlank(), "selected Query image is absent");
    assertEquals(expectedImage, replicaSet.at("/spec/template/spec/containers/0/image").asText());
    for (String attribute : List.of("policy-generation", "policy-digest")) {
      final String key = "simplematch.io~1" + attribute;
      final String expected = deployment.at("/spec/template/metadata/annotations/" + key).asText();
      assertEquals(expected, replicaSet.at("/spec/template/metadata/annotations/" + key).asText(),
          "Query ReplicaSet policy differs from the Deployment template");
      assertEquals(expected, pod.at("/metadata/annotations/" + key).asText(),
          "Query Pod policy annotation differs from its controlling generation");
    }
  }

  private static JsonNode controller(JsonNode references, String kind) {
    final var owners = java.util.stream.StreamSupport.stream(references.spliterator(), false)
        .filter(owner -> owner.path("controller").asBoolean()).toList();
    assertEquals(1, owners.size(), "Query resource must have exactly one controller");
    assertEquals(kind, owners.getFirst().path("kind").asText());
    assertFalse(owners.getFirst().path("uid").asText().isBlank());
    return owners.getFirst();
  }

  private JsonNode queryPods() throws Exception {
    return target.client().read("get", "pods", "-l",
        "app.kubernetes.io/name=query-service,app.kubernetes.io/component=java-service,"
            + OwnedNamespace.OWNER_LABEL + "=" + target.run().runId(),
        "-o", "json").required("items");
  }

  private void requireNamespace() throws Exception {
    final var namespace = target.client().read("get", "namespace", target.namespace(), "-o", "json");
    assertEquals(ownership.namespaceUid(), namespace.path("metadata").path("uid").asText());
    assertEquals(target.run().runId(), namespace.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  record Owner(String name, String uid, String node, String containerId, String podIp,
      int policyVersion) {}

}
