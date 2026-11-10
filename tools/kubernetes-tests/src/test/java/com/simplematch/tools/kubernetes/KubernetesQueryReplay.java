package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rebuilds only Query-owned projections from immutable Kafka input within the original run. */
final class KubernetesQueryReplay {
  private static final String WORKLOAD = "query-service";
  private static final String OPERATOR_SECRET = "query-replay-operator";
  private static final String MATCHING = "matching.events";
  private static final String ACCOUNT = "account.lifecycle";
  private static final int OFFSET_RESET_ATTEMPTS = 3;
  private static final Duration REPLAY_BUDGET = Duration.ofMinutes(5);
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesScenarioTarget target;
  private final ObjectMapper json = new ObjectMapper();
  private final Path evidence;
  private final String deploymentUid;
  private final String kafkaUid;
  private final JsonNode originalEnvironment;
  private final String operatorToken;

  KubernetesQueryReplay(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    this.context = context;
    target = context.target();
    evidence = Files.createDirectory(target.run().evidence().resolve("query-replay"));
    final var deployment = deployment();
    deploymentUid = deployment.path("metadata").path("uid").asText();
    assertFalse(deploymentUid.isBlank());
    assertEquals(2, deployment.path("spec").path("replicas").asInt(-1));
    assertEquals(2, deployment.path("status").path("readyReplicas").asInt(-1));
    originalEnvironment = deployment.at("/spec/template/spec/containers/0/env").deepCopy();
    assertTrue(originalEnvironment.isArray(), "Query deployment has no canonical environment");
    for (var variable : originalEnvironment) {
      assertFalse("SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED".equals(
          variable.path("name").asText()), "Query rebuild adapter was already enabled");
      assertFalse("SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN".equals(
          variable.path("name").asText()), "Query rebuild operator was already configured");
    }
    final var broker = target.client().read("get", "pod", "kafka-0", "-o", "json");
    assertEquals(target.run().runId(), broker.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    kafkaUid = broker.path("metadata").path("uid").asText();
    assertFalse(kafkaUid.isBlank());
    final byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    operatorToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    assertFalse(operatorToken.equals(context.queryCredentials().readerToken()));
    assertFalse(operatorToken.equals(context.queryCredentials().operationsToken()));
  }

  void verify(KubernetesQueryBusinessRead business, KubernetesQueryExecution execution)
      throws Exception {
    final var start = Map.of(MATCHING, offsets(MATCHING, "-2"),
        ACCOUNT, offsets(ACCOUNT, "-2"));
    final var end = Map.of(MATCHING, offsets(MATCHING, "-1"),
        ACCOUNT, offsets(ACCOUNT, "-1"));
    for (String topic : List.of(MATCHING, ACCOUNT)) {
      for (int partition = 0; partition < 15; partition++) {
        assertTrue(start.get(topic).get(partition) <= end.get(topic).get(partition),
            "Kafka source boundary is not monotonic");
      }
    }
    Files.write(evidence.resolve("source-boundaries.json"),
        json.writeValueAsBytes(Map.of("earliest", start, "latest", end)));
    try (var temporary = new TemporaryAdapter()) {
      temporary.enable();
      resetProjection();
      assertResetCleared(execution);
      awaitInactiveGroups();
      for (String topic : List.of(MATCHING, ACCOUNT)) {
        resetGroup(topic, start.get(topic));
      }
      client().run("", "rollout", "restart", "deployment/" + WORKLOAD);
      client().execute("", REPLAY_BUDGET, "rollout", "status", "deployment/" + WORKLOAD,
          "--timeout=300s").checked();
      awaitCaughtUp(end);
      verifySourceCheckpoints(end);
      business.replayed("replayed-with-operator");
      execution.replayed("cross-replayed-with-operator");
      Files.write(evidence.resolve("rebuild-observation.json"), json.writeValueAsBytes(Map.of(
          "runId", target.run().runId(), "namespace", target.namespace(),
          "deploymentUid", deploymentUid, "sourceBoundaries", end,
          "queryBusinessRebuilt", true)));
    }
    business.replayed();
    execution.replayed();
  }

  private void resetProjection() throws Exception {
    try (var forward = client().forward("service/" + WORKLOAD, 8086,
        evidence.resolve("operator-forward.log"));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      final String path = "http://127.0.0.1:" + forward.port() + "/internal/query/rebuild";
      for (String unauthorized : List.of(context.queryCredentials().readerToken(),
          context.queryCredentials().operationsToken())) {
        assertEquals(401, post(http, path, unauthorized).statusCode(),
            "a Query reader or operations bearer authorized the independent projection reset");
      }
      final var response = post(http, path, operatorToken);
      assertEquals(200, response.statusCode(), "independent Query rebuild was not authorized");
      final var result = json.readTree(response.body());
      assertEquals("RESET_COMPLETE", result.path("status").asText());
      Files.write(evidence.resolve("reset.json"), json.writeValueAsBytes(Map.of(
          "status", result.path("status").asText(), "operatorIndependent", true)));
    }
  }

  private static HttpResponse<String> post(HttpClient http, String url, String token)
      throws Exception {
    final var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
        .header("X-SimpleMatch-Query-Token", token)
        .POST(HttpRequest.BodyPublishers.noBody()).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void assertResetCleared(KubernetesQueryExecution execution) throws Exception {
    final var postgres = new KubernetesPostgres(target);
    final String count = postgres.execute("""
        SELECT count(*) FROM query_service.execution_read_model
        WHERE order_id = :'order_id';
        """, Map.of("order_id", execution.buyOrderId())).strip();
    assertEquals("0", count,
        "Query reset must clear execution rows before replay is allowed to start");
    final String orders = postgres.execute("""
        SELECT count(*) FROM query_service.order_read_model
        WHERE order_id = :'order_id';
        """, Map.of("order_id", execution.buyOrderId())).strip();
    assertEquals("0", orders,
        "Query reset must clear order rows before replay is allowed to start");
  }

  private void awaitInactiveGroups() throws Exception {
    final var budget = OperationBudget.start(Duration.ofSeconds(45));
    while (true) {
      budget.remaining();
      boolean inactive = true;
      for (String group : List.of("query-service-matching-events",
          "query-service-account-lifecycle")) {
        requireBroker();
        final String status = client().execute("", budget.remaining(Duration.ofSeconds(10)),
            "exec", "kafka-0", "-c", "kafka", "--",
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server", "kafka:9092", "--group", group,
            "--describe", "--state").checked();
        inactive &= java.util.regex.Pattern.compile(
            "(?m)^\\s*" + java.util.regex.Pattern.quote(group)
                + "\\s+.*\\bEmpty\\s+0\\s*$").matcher(status).find();
      }
      if (inactive) {
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(300)));
    }
  }

  private void verifySourceCheckpoints(Map<String, Map<Integer, Long>> end) throws Exception {
    final var postgres = new KubernetesPostgres(target);
    final var result = json.readTree(postgres.execute("""
        SELECT COALESCE(json_agg(json_build_object(
          'topic', source_topic, 'partition', partition_id,
          'offset', last_processed_offset, 'state', recovery_state)
          ORDER BY source_topic, partition_id), '[]'::json)::text
        FROM query_service.projection_checkpoint
        WHERE source_topic IN ('matching.events', 'account.lifecycle');
        """, Map.of()));
    assertTrue(result.isArray());
    final var seen = new java.util.HashSet<String>();
    for (var row : result) {
      final String topic = row.path("topic").asText();
      final int partition = row.path("partition").asInt(-1);
      assertTrue(end.containsKey(topic) && partition >= 0 && partition < 15);
      final String key = topic + ":" + partition;
      assertTrue(seen.add(key), "duplicate Query source checkpoint");
      assertEquals("READY", row.path("state").asText());
      assertEquals(end.get(topic).get(partition) - 1,
          row.path("offset").asLong(-1),
          "Query source checkpoint differs from the captured Kafka high-water offset: " + key);
    }
    for (String topic : List.of(MATCHING, ACCOUNT)) {
      for (int partition = 0; partition < 15; partition++) {
        if (end.get(topic).get(partition) > 0) {
          assertTrue(seen.contains(topic + ":" + partition),
              "Query replay did not reconstruct a required source checkpoint");
        }
      }
    }
    Files.write(evidence.resolve("replayed-checkpoints.json"), json.writeValueAsBytes(result));
  }

  private Map<Integer, Long> offsets(String topic, String time) throws Exception {
    requireBroker();
    final var output = client().execute("", Duration.ofSeconds(30), "exec", "kafka-0",
        "-c", "kafka", "--", "/opt/kafka/bin/kafka-get-offsets.sh",
        "--bootstrap-server", "kafka:9092", "--topic", topic, "--time", time).checked();
    return parseOffsets(topic, output);
  }

  static Map<Integer, Long> parseOffsets(String topic, String output) {
    final var rows = new HashMap<Integer, Long>();
    for (String line : output.lines().filter(value -> !value.isBlank()).toList()) {
      final List<String> tokens = com.google.common.base.Splitter.on(':').splitToList(line.strip());
      if (tokens.size() != 3 || !topic.equals(tokens.get(0))) {
        throw new IllegalArgumentException("Kafka source offset has an unexpected topic or shape");
      }
      final int partition = Integer.parseInt(tokens.get(1));
      final long offset = Long.parseLong(tokens.get(2));
      if (partition < 0 || partition >= 15 || offset < 0
          || rows.putIfAbsent(partition, offset) != null) {
        throw new IllegalArgumentException("Kafka source boundary contains an invalid partition");
      }
    }
    if (rows.size() != 15) {
      throw new IllegalArgumentException("Kafka source boundary requires exactly 15 partitions");
    }
    return Map.copyOf(rows);
  }

  private void resetGroup(String topic, Map<Integer, Long> earliest) throws Exception {
    requireBroker();
    final String group = MATCHING.equals(topic)
        ? "query-service-matching-events" : "query-service-account-lifecycle";
    final String filename = "/tmp/query-reset-" + UUID.randomUUID() + ".txt";
    final var offsetsFile = new StringBuilder();
    for (int partition = 0; partition < 15; partition++) {
      offsetsFile.append(topic).append(',').append(partition).append(',')
          .append(earliest.get(partition)).append('\n');
    }
    try {
      client().execute(offsetsFile.toString(), Duration.ofSeconds(20),
          "exec", "-i", "kafka-0", "-c", "kafka", "--",
          "sh", "-c", "cat > " + filename).checked();
      // Kafka's administrative CLI can emit an error with exit code zero. Accept
      // neither that output nor an incomplete group describe as a successful reset.
      // All retries target the same captured boundaries while Query is stopped.
      for (int attempt = 1; attempt <= OFFSET_RESET_ATTEMPTS; attempt++) {
        requireBroker();
        final var output = client().execute("", Duration.ofSeconds(40),
            "exec", "kafka-0", "-c", "kafka", "--",
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server", "kafka:9092", "--group", group,
            "--reset-offsets", "--from-file", filename, "--execute").checked();
        Files.writeString(evidence.resolve(group + "-reset-attempt-" + attempt + ".txt"),
            DiagnosticText.redact(output));
        requireBroker();
        final var groupState = client().execute("", Duration.ofSeconds(25),
            "exec", "kafka-0", "-c", "kafka", "--",
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server", "kafka:9092", "--group", group, "--describe").checked();
        Files.writeString(evidence.resolve(group + "-describe-attempt-" + attempt + ".txt"),
            DiagnosticText.redact(groupState));
        if (resetReportedAtBoundaries(group, topic, output, earliest)
            && groupAtBoundaries(topic, groupState, earliest)) {
          Files.writeString(evidence.resolve(group + "-reset.txt"),
              DiagnosticText.redact(output));
          return;
        }
        if (attempt < OFFSET_RESET_ATTEMPTS) {
          Thread.sleep(500);
        }
      }
      throw new IllegalStateException("Kafka reset did not report and commit all 15 captured "
          + "offsets after " + OFFSET_RESET_ATTEMPTS + " bounded attempts: " + group);
    } finally {
      requireBroker();
      client().execute("", Duration.ofSeconds(20), "exec", "kafka-0", "-c", "kafka",
          "--", "rm", "-f", filename).checked();
    }
  }

  private void awaitCaughtUp(Map<String, Map<Integer, Long>> end) throws Exception {
    final var budget = OperationBudget.start(REPLAY_BUDGET);
    while (true) {
      budget.remaining();
      boolean ready = true;
      for (String topic : List.of(MATCHING, ACCOUNT)) {
        final String group = MATCHING.equals(topic)
            ? "query-service-matching-events" : "query-service-account-lifecycle";
        final String output = client().execute("", budget.remaining(Duration.ofSeconds(25)),
            "exec", "kafka-0", "-c", "kafka", "--",
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server", "kafka:9092", "--group", group, "--describe").checked();
        ready &= groupCaughtUp(topic, output, end.get(topic));
        Files.writeString(evidence.resolve(group + "-status.txt"), DiagnosticText.redact(output));
      }
      if (ready) {
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(500)));
    }
  }

  static boolean groupCaughtUp(String topic, String output, Map<Integer, Long> end) {
    if (end.size() != 15) {
      throw new IllegalArgumentException("replay comparison needs all source partitions");
    }
    final var seen = new java.util.HashSet<Integer>();
    boolean caughtUp = true;
    for (String line : output.lines().toList()) {
      final var columns = columns(line);
      if (columns.size() < 6 || !topic.equals(columns.get(1))) {
        continue;
      }
      try {
        final int partition = Integer.parseInt(columns.get(2));
        final long current = Long.parseLong(columns.get(3));
        final long lag = Long.parseLong(columns.get(5));
        if (partition < 0 || partition >= 15 || !seen.add(partition)
            || !end.containsKey(partition)) {
          throw new IllegalArgumentException("Query replay has an unexpected Kafka partition");
        }
        caughtUp &= current == end.get(partition) && lag == 0;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return caughtUp && seen.size() == 15;
  }

  static boolean groupAtBoundaries(String topic, String output, Map<Integer, Long> offsets) {
    if (offsets.size() != 15) {
      return false;
    }
    final var partitions = new java.util.HashSet<Integer>();
    for (String line : output.lines().toList()) {
      final var fields = java.util.regex.Pattern.compile("\\S+").matcher(line);
      final var columns = new ArrayList<String>();
      while (fields.find()) {
        columns.add(fields.group());
      }
      if (columns.size() < 6 || !topic.equals(columns.get(1))) {
        continue;
      }
      try {
        final int partition = Integer.parseInt(columns.get(2));
        final long current = Long.parseLong(columns.get(3));
        if (!partitions.add(partition) || !offsets.containsKey(partition)
            || current != offsets.get(partition)) {
          return false;
        }
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return partitions.size() == 15;
  }

  static boolean resetReportedAtBoundaries(String group, String topic, String output,
      Map<Integer, Long> offsets) {
    if (offsets.size() != 15) {
      return false;
    }
    final var seen = new java.util.HashSet<Integer>();
    for (String line : output.lines().map(String::strip).filter(value -> !value.isEmpty()).toList()) {
      if (line.startsWith("GROUP ")) {
        continue;
      }
      final var columns = columns(line);
      if (columns.size() != 4 || !group.equals(columns.get(0)) || !topic.equals(columns.get(1))) {
        return false;
      }
      try {
        final int partition = Integer.parseInt(columns.get(2));
        final long offset = Long.parseLong(columns.get(3));
        if (partition < 0 || partition >= 15 || !seen.add(partition)
            || !offsets.containsKey(partition) || offset != offsets.get(partition)) {
          return false;
        }
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return seen.size() == 15;
  }

  private static List<String> columns(String line) {
    final var matcher = java.util.regex.Pattern.compile("\\S+").matcher(line);
    final var columns = new ArrayList<String>();
    while (matcher.find()) {
      columns.add(matcher.group());
    }
    return columns;
  }

  private JsonNode deployment() throws Exception {
    final var observed = client().read("get", "deployment", WORKLOAD, "-o", "json");
    assertEquals(target.run().runId(), observed.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    assertEquals(target.replacements().get("simplematch/query-service:local"),
        observed.at("/spec/template/spec/containers/0/image").asText());
    return observed;
  }

  private void requireDeploymentUid() throws Exception {
    assertEquals(deploymentUid, deployment().path("metadata").path("uid").asText(),
        "Query Deployment changed outside this replay");
  }

  private void requireBroker() throws Exception {
    final var broker = client().read("get", "pod", "kafka-0", "-o", "json");
    assertEquals(kafkaUid, broker.path("metadata").path("uid").asText());
    assertEquals(target.run().runId(), broker.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
  }

  private KubernetesClient client() {
    return target.client();
  }

  private void awaitReplicas(int replicas) throws Exception {
    final var budget = OperationBudget.start(Duration.ofMinutes(3));
    while (true) {
      budget.remaining();
      requireDeploymentUid();
      final var deployment = deployment();
      final var pods = client().read("get", "pods", "-l",
          "app.kubernetes.io/name=query-service,"
              + OwnedNamespace.OWNER_LABEL + "=" + target.run().runId(),
          "-o", "json").required("items");
      if (deployment.path("spec").path("replicas").asInt(-1) == replicas
          && deployment.path("status").path("readyReplicas").asInt() == replicas
          && pods.size() == replicas) {
        return;
      }
      Thread.sleep(budget.remaining(Duration.ofMillis(400)));
    }
  }

  private final class TemporaryAdapter implements AutoCloseable {
    private String createdSecretUid;
    private boolean scaled;
    private boolean environmentChanged;

    void enable() throws Exception {
      requireDeploymentUid();
      final var fence = new KubernetesQueryStopFence(context);
      final var originalTasks = fence.capture();
      final var secret = KubernetesDocuments.data("Secret", OPERATOR_SECRET,
          Map.of("SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN", operatorToken));
      target.create(secret);
      createdSecretUid = client().read("get", "secret", OPERATOR_SECRET, "-o", "json")
          .path("metadata").path("uid").asText();
      assertFalse(createdSecretUid.isBlank());
      scaled = true;
      client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=0");
      fence.awaitStopped(originalTasks, Duration.ofSeconds(20));
      final var vars = List.of(
          Map.of("name", "SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED", "value", "true"),
          Map.of("name", "SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN",
              "valueFrom", Map.of("secretKeyRef", Map.of("name", OPERATOR_SECRET,
                  "key", "SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN"))));
      final var operations = new ArrayList<Map<String, Object>>();
      for (var value : vars) {
        operations.add(Map.of("op", "add", "path",
            "/spec/template/spec/containers/0/env/-", "value", value));
      }
      environmentChanged = true;
      client().run("", "patch", "deployment", WORKLOAD, "--type=json",
          "--patch", json.writeValueAsString(operations));
      client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=1");
      client().execute("", Duration.ofMinutes(3), "rollout", "status",
          "deployment/" + WORKLOAD, "--timeout=180s").checked();
      awaitReplicas(1);
    }

    @Override
    public void close() throws Exception {
      if (scaled) {
        requireDeploymentUid();
        client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=0");
        awaitReplicas(0);
      }
      if (environmentChanged) {
        requireDeploymentUid();
        final var patch = List.of(Map.of("op", "replace",
            "path", "/spec/template/spec/containers/0/env", "value", originalEnvironment));
        client().run("", "patch", "deployment", WORKLOAD, "--type=json",
            "--patch", json.writeValueAsString(patch));
        assertEquals(originalEnvironment, deployment().at("/spec/template/spec/containers/0/env"),
            "Query rebuild operator environment remained after restoration");
      }
      if (scaled) {
        client().run("", "scale", "deployment/" + WORKLOAD, "--replicas=2");
        client().execute("", Duration.ofMinutes(3), "rollout", "status",
            "deployment/" + WORKLOAD, "--timeout=180s").checked();
        awaitReplicas(2);
        context.workloadImages().verifySelected(client(), target.run(),
            "query-replay-restored", WORKLOAD, 2);
      }
      if (createdSecretUid != null) {
        final var current = client().read("get", "secret", OPERATOR_SECRET, "-o", "json");
        assertEquals(target.run().runId(), current.path("metadata").path("labels")
            .path(OwnedNamespace.OWNER_LABEL).asText());
        assertEquals(createdSecretUid, current.path("metadata").path("uid").asText());
        client().run("", "delete", "secret", OPERATOR_SECRET, "--wait=true");
      }
    }
  }
}
