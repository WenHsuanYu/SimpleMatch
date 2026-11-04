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

    for (int version : List.of(2, 3)) {
      final var generation = context.queryCredentials().replacement(version, dsn);
      expectedSecretUid = replace(generation, original, deploymentUid, expectedSecretUid);
      final var retiredTokens = List.copyOf(previous);
      previous.add(generation.readerToken());
      previous.add(generation.operationsToken());
      previous.add(generation.retainedReaderToken());
      final var current = owners();
      verifyServing(version, current, generation, retiredTokens, account, order);
      // A new generation must revoke every token from earlier generations.
      assertEquals(2, current.size());
    }
  }

  private String replace(KubernetesQueryCredentials.Generation generation, List<Owner> baseline,
      String deploymentUid, String expectedSecretUid) throws Exception {
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
    final var tasks = new ArrayList<Task>();
    for (var owner : old) {
      tasks.add(captureTask(owner));
    }
    target.client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=0");
    awaitOldGenerationStopped(old, tasks);

    // The old policy is never restored after this point, including on a failed rollout.
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
    target.client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=2");
    target.client().execute("", START_BUDGET, "rollout", "status", "deployment/" + WORKLOAD,
        "--timeout=180s").checked();
    awaitNewGeneration(generation.version(), old, deploymentUid, policyDigest);
    Files.write(evidence.resolve("v" + generation.version() + "-activation.json"),
        json.writeValueAsBytes(Map.of("policyVersion", generation.version(),
            "policyDigest", policyDigest, "secretUid",
            nextSecret.path("metadata").path("uid").asText(), "oldPods", old, "newPods", owners())));
    return nextSecret.path("metadata").path("uid").asText();
  }

  private void awaitOldGenerationStopped(List<Owner> old, List<Task> tasks) throws Exception {
    final var budget = OperationBudget.start(STOP_BUDGET);
    while (true) {
      budget.remaining();
      requireNamespace();
      final var deployment = deployment();
      final var pods = queryPods();
      if (deployment.path("spec").path("replicas").asInt(-1) == 0
          && deployment.path("status").path("replicas").asInt() == 0
          && deployment.path("status").path("readyReplicas").asInt() == 0
          && pods.isEmpty()) {
        if (allQueryTasksStopped(tasks, budget)) {
          Files.write(evidence.resolve("v" + nextVersionFromOld(old) + "-fence.json"),
              json.writeValueAsBytes(Map.of("runId", target.run().runId(),
                  "oldPods", old, "observedTasks", tasks,
                  "observedZeroPods", true, "oldTasksStopped", true)));
          return;
        }
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(300)));
    }
  }

  private static int nextVersionFromOld(List<Owner> old) {
    return old.getFirst().policyVersion() + 1;
  }

  private Task captureTask(Owner owner) throws Exception {
    requireWorker(owner.node(), Duration.ofSeconds(10));
    final var detail = json.readTree(docker.run(ownership.docker().context().command(
        "exec", owner.node(), "crictl", "inspect", owner.containerId()), "",
        Duration.ofSeconds(10)).checked());
    assertEquals("CONTAINER_RUNNING", detail.at("/status/state").asText());
    assertEquals(owner.uid(), detail.at("/status/labels/io.kubernetes.pod.uid").asText());
    assertEquals(WORKLOAD, detail.at("/status/metadata/name").asText());
    final long pid = detail.at("/info/pid").asLong(-1);
    assertTrue(pid > 0, "Query CRI task has no running process ID");
    final String start = processStart(owner.node(), pid, Duration.ofSeconds(10));
    assertFalse(start.isBlank(), "Query task has no process start identity");
    return new Task(owner.node(), owner.containerId(), owner.uid(), pid, start);
  }

  private boolean allQueryTasksStopped(List<Task> captured, OperationBudget budget)
      throws Exception {
    for (String node : KubernetesImages.workerNodes(target.client())) {
      requireWorker(node, budget.remaining(Duration.ofSeconds(5)));
      final String running = docker.run(ownership.docker().context().command(
          "exec", node, "crictl", "ps", "-o", "json"), "",
          budget.remaining(Duration.ofSeconds(5))).checked();
      final var tasks = json.readTree(running).path("containers");
      assertTrue(tasks.isArray(), "CRI returned no authoritative running-task list");
      for (var task : tasks) {
        final String name = task.path("metadata").path("name").asText();
        final String namespace = task.path("labels")
            .path("io.kubernetes.pod.namespace").asText();
        if (captured.stream().anyMatch(previous ->
            previous.container().equals(task.path("id").asText()))
            || (WORKLOAD.equals(name)
                && (namespace.isBlank() || target.namespace().equals(namespace)))) {
          return false;
        }
      }
    }
    for (var task : captured) {
      final String running = docker.run(ownership.docker().context().command(
          "exec", task.node(), "ctr", "-n", "k8s.io", "tasks", "ls", "-q"), "",
          budget.remaining(Duration.ofSeconds(5))).checked();
      if (running.lines().anyMatch(task.container()::equals)) {
        return false;
      }
      final var inspection = docker.run(ownership.docker().context().command(
          "exec", task.node(), "crictl", "inspect", task.container()), "",
          budget.remaining(Duration.ofSeconds(5)));
      if (inspection.exitCode() == 0) {
        final var detail = json.readTree(inspection.checked());
        if ("CONTAINER_RUNNING".equals(detail.at("/status/state").asText())) {
          return false;
        }
      } else if (!inspection.error().toLowerCase(java.util.Locale.ROOT).contains("not found")
          && !inspection.error().toLowerCase(java.util.Locale.ROOT).contains("notfound")) {
        inspection.checked();
        throw new IllegalStateException("Query task inspection failed without proven absence");
      }
      final var process = docker.run(ownership.docker().context().command(
          "exec", task.node(), "cat", "/proc/" + task.pid() + "/stat"), "",
          budget.remaining(Duration.ofSeconds(5)));
      if (process.exitCode() == 0) {
        if (task.start().equals(startTime(process.checked()))) {
          return false;
        }
      } else if (!process.error().contains("No such file or directory")) {
        process.checked();
        throw new IllegalStateException("Query process absence is unproven");
      }
    }
    return true;
  }

  private void requireWorker(String node, Duration budget) throws Exception {
    final String id = json.readTree(docker.run(ownership.docker().context().command(
        "inspect", node), "", budget).checked()).get(0).path("Id").asText();
    assertTrue(ownership.cluster().nodeIds().contains(id),
        "Query worker differs from the original Docker cluster identity");
  }

  private String processStart(String node, long pid, Duration budget) throws Exception {
    return startTime(docker.run(ownership.docker().context().command(
        "exec", node, "cat", "/proc/" + pid + "/stat"), "", budget).checked());
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
      String policyDigest) throws Exception {
    final var budget = OperationBudget.start(START_BUDGET);
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
          containerId.substring("containerd://".length()), versionFromPod(pod)));
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

  record Owner(String name, String uid, String node, String containerId, int policyVersion) {}

  record Task(String node, String container, String podUid, long pid, String start) {}
}
