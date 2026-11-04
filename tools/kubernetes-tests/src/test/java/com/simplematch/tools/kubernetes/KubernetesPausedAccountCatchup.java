package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Proves that Kafka events published with zero Query tasks reached durable Query projections. */
final class KubernetesPausedAccountCatchup {
  private static final String TOPIC = "account.lifecycle";
  private final String jobName;
  private static final String CLASS_KEY = "publisher.class";
  private static final String SEED_CLASS_KEY = "seed.class";
  private static final String CLASS_PATH =
      "com/simplematch/tools/kubernetes/KubernetesPausedAccountPublisher.class";
  private static final String SEED_CLASS_PATH =
      "com/simplematch/tools/kubernetes/KubernetesPausedAccountPublisher$Seed.class";
  private static final String RECEIPT_PREFIX = "QUERY_PAUSED_ACCOUNT_RECEIPTS=";
  private static final Pattern END_OFFSET = Pattern.compile(
      "^account\\.lifecycle:([0-9]+):([0-9]+)$");
  private static final String CHECKPOINTS = """
      SELECT COALESCE(json_agg(json_build_object(
        'partition', partition_id, 'offset', last_processed_offset,
        'state', recovery_state) ORDER BY partition_id), '[]'::json)::text
      FROM query_service.projection_checkpoint
      WHERE source_topic = 'account.lifecycle';
      """;
  private static final String PROJECTIONS = """
      SELECT json_build_object(
        'inbox', (SELECT COALESCE(json_agg(json_build_object(
          'eventId', event_id::text, 'topic', source_topic, 'partition', partition_id,
          'offset', offset_value, 'payloadSha256', encode(payload_sha256, 'hex'))), '[]'::json)
          FROM query_service.projection_inbox
          WHERE event_id IN (:'first', :'second')),
        'accounts', (SELECT COALESCE(json_agg(json_build_object(
          'accountId', account_id::text, 'eventId', source_event_id,
          'state', lifecycle_state, 'notional', reserved_notional_units)), '[]'::json)
          FROM query_service.account_summary_read_model
          WHERE account_id IN (:'account0', :'account1'))
      )::text;
      """;

  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final KubernetesScenarioTarget target;
  private final KubernetesPostgres postgres;
  private final ObjectMapper json = new ObjectMapper();
  private final Path evidence;
  private final String image;
  private final List<KubernetesPausedAccountPublisher.Seed> seeds;
  private final Map<String, String> sqlParameters;
  private JsonNode stoppedCheckpoints;
  private List<Receipt> receipts;
  private Map<Integer, Long> boundary;

  KubernetesPausedAccountCatchup(KubernetesBusinessEnvironment.PublicOrder context, String image,
      Path parentEvidence, int generation) throws Exception {
    if (generation != 2 && generation != 3) {
      throw new IllegalArgumentException("paused Account publication requires Query V2 or V3");
    }
    jobName = "query-paused-account-events-v" + generation;
    this.context = context;
    target = context.target();
    postgres = new KubernetesPostgres(target);
    evidence = Files.createDirectory(parentEvidence.resolve("account-catchup-v" + generation));
    this.image = image;
    seeds = List.of(
        new KubernetesPausedAccountPublisher.Seed(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0, 3000),
        new KubernetesPausedAccountPublisher.Seed(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 4000));
    sqlParameters = Map.of("first", seeds.get(0).eventId(), "second", seeds.get(1).eventId(),
        "account0", seeds.get(0).accountId(), "account1", seeds.get(1).accountId());
  }

  void publishWhileStopped(KubernetesQueryStopFence fence,
      List<KubernetesQueryStopFence.Task> oldTasks) throws Exception {
    fence.awaitStopped(oldTasks, Duration.ofSeconds(20));
    stoppedCheckpoints = checkpoints();
    assertTrue(emptyProjections(projections()),
        "paused Account fixture already exists before broker publication");
    final String seedJson = json.writeValueAsString(Map.of("seeds", seeds));
    final String publisherBytes = compiled("KubernetesPausedAccountPublisher.class");
    final String seedBytes = compiled("KubernetesPausedAccountPublisher$Seed.class");
    target.create(json.valueToTree(Map.of(
        "apiVersion", "v1", "kind", "ConfigMap",
        "metadata", Map.of("name", jobName),
        "immutable", true,
        "data", Map.of("seeds.json", seedJson),
        "binaryData", Map.of(
            CLASS_KEY, publisherBytes, SEED_CLASS_KEY, seedBytes))));
    final var input = target.client().read("get", "configmap", jobName, "-o", "json");
    assertEquals(target.run().runId(),
        input.path("metadata").path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
    assertTrue(input.path("immutable").asBoolean());
    final String inputUid = input.path("metadata").path("uid").asText();
    assertFalse(inputUid.isBlank());
    assertEquals(seedJson, input.path("data").path("seeds.json").asText());
    assertEquals(publisherBytes, input.path("binaryData").path(CLASS_KEY).asText());
    assertEquals(seedBytes, input.path("binaryData").path(SEED_CLASS_KEY).asText());
    target.create(job());
    target.client().execute("", Duration.ofSeconds(100), "wait",
        "--for=condition=complete", "job/" + jobName, "--timeout=90s").checked();
    final var actual = target.client().read("get", "job", jobName, "-o", "json");
    assertEquals(target.run().runId(),
        actual.path("metadata").path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
    assertFalse(actual.path("metadata").path("uid").asText().isBlank());
    assertTrue(KubernetesJobs.condition(actual, "Complete"), "Account publisher Job did not finish");
    assertEquals(inputUid, target.client().read("get", "configmap", jobName, "-o", "json")
        .path("metadata").path("uid").asText(), "Account input changed during publication");
    context.workloadImages().verifySelected(target.client(), target.run(),
        "query-account-publisher-" + jobName, jobName, 1);
    receipts = parseReceipts(target.client().run("", "logs", "job/" + jobName));
    fence.awaitStopped(oldTasks, Duration.ofSeconds(20));
    assertEquals(stoppedCheckpoints, checkpoints(),
        "a Query Account checkpoint advanced while both old Query tasks were stopped");
    assertTrue(emptyProjections(projections()),
        "Query consumed paused Account input before its replacement generation started");
    boundary = kafkaEndOffsets();
    for (var receipt : receipts) {
      assertTrue(boundary.get(receipt.partition()) > receipt.offset(),
          "broker end offset precedes the acknowledged Account event");
    }
    Files.write(evidence.resolve("published.json"), json.writeValueAsBytes(Map.of(
        "synthetic", true, "receipts", receipts, "boundary", boundary,
        "oldTasksStopped", true, "queryCheckpointUnchanged", true)));
  }

  private static String compiled(String name) throws Exception {
    try (InputStream input = java.util.Objects.requireNonNull(
        KubernetesPausedAccountPublisher.class.getResourceAsStream(name),
        "selected compiled Account publisher class is absent")) {
      final byte[] bytes = input.readAllBytes();
      assertTrue(bytes.length > 0, "selected Account publisher has empty bytecode");
      return Base64.getEncoder().encodeToString(bytes);
    }
  }

  private JsonNode job() {
    final String classpath = "/workspace/BOOT-INF/classes:/workspace/BOOT-INF/lib/*:/fixture";
    final var container = Map.of("name", jobName, "image", image, "imagePullPolicy", "Never",
        "command", List.of("/cnb/lifecycle/launcher", "--", "java", "-cp", classpath,
            KubernetesPausedAccountPublisher.class.getName(), "/fixture/seeds.json"),
        "volumeMounts", List.of(Map.of(
            "name", "fixture", "mountPath", "/fixture", "readOnly", true)));
    final var mount = Map.of("name", "fixture", "configMap", Map.of("name", jobName,
        "items", List.of(Map.of("key", "seeds.json", "path", "seeds.json"),
            Map.of("key", CLASS_KEY, "path", CLASS_PATH),
            Map.of("key", SEED_CLASS_KEY, "path", SEED_CLASS_PATH))));
    final var pod = Map.of(
        "metadata", Map.of("labels", Map.of(
            "app.kubernetes.io/name", jobName, "app.kubernetes.io/part-of", "simplematch")),
        "spec", Map.of("restartPolicy", "Never", "automountServiceAccountToken", false,
            "activeDeadlineSeconds", 90,
            "nodeSelector", Map.of("simplematch.io/node-pool", "local-resilience"),
            "containers", List.of(container), "volumes", List.of(mount)));
    return json.valueToTree(Map.of("apiVersion", "batch/v1", "kind", "Job",
        "metadata", Map.of("name", jobName),
        "spec", Map.of("backoffLimit", 0, "activeDeadlineSeconds", 90, "template", pod)));
  }

  private List<Receipt> parseReceipts(String output) throws Exception {
    final var reports = output.lines().filter(line -> line.startsWith(RECEIPT_PREFIX)).toList();
    assertEquals(1, reports.size(), "Account publisher produced no unique broker-ACK receipt");
    final var report = json.readTree(reports.getFirst().substring(RECEIPT_PREFIX.length()));
    assertTrue(report.path("synthetic").asBoolean());
    final var records = report.path("records");
    assertEquals(seeds.size(), records.size());
    final var result = new ArrayList<Receipt>();
    for (int index = 0; index < seeds.size(); index++) {
      final var row = records.get(index);
      final var seed = seeds.get(index);
      assertEquals(seed.accountId(), row.path("accountId").asText());
      assertEquals(seed.eventId(), row.path("eventId").asText());
      assertEquals(seed.partition(), row.path("partition").asInt(-1));
      assertEquals(seed.reservedNotionalUnits(), row.path("reservedNotionalUnits").asLong(-1));
      final long offset = row.path("offset").asLong(-1);
      final String hash = row.path("payloadSha256").asText();
      assertTrue(offset >= 0 && hash.matches("[0-9a-f]{64}"),
          "Account publisher receipt lacks an exact Kafka position or payload digest");
      result.add(new Receipt(seed.accountId(), seed.eventId(), seed.partition(), offset,
          hash, seed.reservedNotionalUnits()));
    }
    return List.copyOf(result);
  }

  private Map<Integer, Long> kafkaEndOffsets() throws Exception {
    final var kafka = target.client().read("get", "pod", "kafka-0", "-o", "json");
    assertEquals(target.run().runId(),
        kafka.path("metadata").path("labels").path(OwnedNamespace.OWNER_LABEL).asText());
    final String output = target.client().execute("", Duration.ofSeconds(25),
        "exec", "kafka-0", "-c", "kafka", "--",
        "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "kafka:9092",
        "--topic", TOPIC, "--time", "-1").checked();
    final var result = new HashMap<Integer, Long>();
    for (String line : output.lines().filter(value -> !value.isBlank()).toList()) {
      final var match = END_OFFSET.matcher(line);
      if (!match.matches()) {
        throw new IllegalStateException("unexpected Kafka end-offset observation");
      }
      final int partition = Integer.parseInt(match.group(1));
      final long end = Long.parseLong(match.group(2));
      if (partition < 0 || partition >= 15 || end < 0 || result.put(partition, end) != null) {
        throw new IllegalStateException("Kafka Account partition identity is ambiguous");
      }
    }
    assertEquals(15, result.size(), "Account Kafka end-offset boundary is incomplete");
    return Map.copyOf(result);
  }

  void verifyCatchup(OperationBudget recoveryBudget) throws Exception {
    assertTrue(receipts != null && boundary != null, "no acknowledged paused Account publication");
    while (true) {
      final var remaining = recoveryBudget.remaining();
      final var checkpoints = checkpoints(remaining);
      final var projections = projections(recoveryBudget.remaining());
      if (caughtUp(checkpoints, projections, boundary, receipts)) {
        Files.write(evidence.resolve("caught-up.json"), json.writeValueAsBytes(Map.of(
            "synthetic", true, "boundary", boundary, "receipts", receipts,
            "checkpoint", checkpoints, "durableProjections", projections)));
        return;
      }
      Thread.sleep(recoveryBudget.remaining(Duration.ofMillis(250)));
    }
  }

  private JsonNode checkpoints() throws Exception {
    return checkpoints(Duration.ofSeconds(15));
  }

  private JsonNode checkpoints(Duration timeout) throws Exception {
    return json.readTree(postgres.execute(CHECKPOINTS, Map.of(),
        timeout.compareTo(Duration.ofSeconds(15)) < 0 ? timeout : Duration.ofSeconds(15)));
  }

  private JsonNode projections() throws Exception {
    return projections(Duration.ofSeconds(15));
  }

  private JsonNode projections(Duration timeout) throws Exception {
    return json.readTree(postgres.execute(PROJECTIONS, sqlParameters,
        timeout.compareTo(Duration.ofSeconds(15)) < 0 ? timeout : Duration.ofSeconds(15)));
  }

  private static boolean emptyProjections(JsonNode observed) {
    return observed.path("inbox").size() == 0 && observed.path("accounts").size() == 0;
  }

  static boolean caughtUp(JsonNode checkpoints, JsonNode state, Map<Integer, Long> boundary,
      List<Receipt> receipts) {
    if (checkpoints == null || !checkpoints.isArray()
        || !state.path("inbox").isArray() || !state.path("accounts").isArray()) {
      return false;
    }
    final var positions = new HashMap<Integer, Long>();
    for (var checkpoint : checkpoints) {
      if (!"READY".equals(checkpoint.path("state").asText())) {
        return false;
      }
      positions.put(checkpoint.path("partition").asInt(-1), checkpoint.path("offset").asLong(-1));
    }
    for (var end : boundary.entrySet()) {
      if (end.getValue() > 0 && positions.getOrDefault(end.getKey(), -1L) != end.getValue() - 1) {
        return false;
      }
    }
    if (state.path("inbox").size() != receipts.size()
        || state.path("accounts").size() != receipts.size()) {
      return false;
    }
    for (var receipt : receipts) {
      int inboxCount = 0;
      int accountCount = 0;
      for (var record : state.path("inbox")) {
        if (receipt.eventId().equals(record.path("eventId").asText())
            && TOPIC.equals(record.path("topic").asText())
            && receipt.partition() == record.path("partition").asInt(-1)
            && receipt.offset() == record.path("offset").asLong(-1)
            && receipt.payloadSha256().equals(record.path("payloadSha256").asText())) {
          inboxCount++;
        }
      }
      for (var account : state.path("accounts")) {
        if (receipt.accountId().equals(account.path("accountId").asText())
            && receipt.eventId().equals(account.path("eventId").asText())
            && "ACCOUNT_LIFECYCLE_STATE_RESERVED".equals(account.path("state").asText())
            && receipt.reservedNotionalUnits() == account.path("notional").asLong(-1)) {
          accountCount++;
        }
      }
      if (inboxCount != 1 || accountCount != 1) {
        return false;
      }
    }
    return true;
  }

  record Receipt(String accountId, String eventId, int partition, long offset,
      String payloadSha256, long reservedNotionalUnits) {}
}
