package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Deploys canonical RM-1 dependencies in their explicit migration/topic/barrier order. */
final class RiskCommandDeployment {
  private final KubernetesSettings settings;
  private final KubernetesPrerequisites inputs;
  private final KubernetesImages images;
  private final KubernetesScenarioTarget target;

  RiskCommandDeployment(KubernetesSettings settings, KubernetesPrerequisites inputs,
      KubernetesImages images, KubernetesScenarioTarget target) {
    this.settings = settings;
    this.inputs = inputs;
    this.images = images;
    this.target = target;
  }

  void start() throws Exception {
    for (var document : inputs.profile().documents()) {
      if (!Set.of("Deployment", "StatefulSet", "Job").contains(document.path("kind").asText())) {
        target.create(document);
      }
    }
    configureInputs();
    for (String name : java.util.List.of("postgres", "kafka")) {
      createNamed(name);
    }
    target.client().waitFor("jsonpath={.status.readyReplicas}=3", "statefulset/kafka");
    target.client().waitFor("jsonpath={.status.readyReplicas}=1", "statefulset/postgres");
    for (String name : java.util.List.of("account-service-flyway", "risk-service-flyway",
        "kafka-topic-provisioning")) {
      createNamed(name);
      KubernetesJobs.awaitComplete(target.client(), name, target.run());
    }
    publishOpenBarriers();
    KindHealth.requireImageHeadroom(settings.dockerContext().commands(settings.root()),
        settings.dockerContext(), target.client(), target.run().evidence(), "after-bootstrap");
    KindHealth.requireStable(target.client(), target.run().evidence(), "after-bootstrap");
    for (String name : java.util.List.of("account-service", "risk-service", "kafka-connect")) {
      createNamed(name);
      target.client().execute("", Duration.ofMinutes(11), "rollout", "status",
          "deployment/" + name, "--timeout=600s").checked();
    }
    registerRiskConnector();
  }

  private void configureInputs() throws Exception {
    final ObjectNode artifact = inputs.input().configMap();
    artifact.withObject("metadata").put("name", "matching-daily-artifact");
    target.create(artifact);
    final String matching = images.identities().get("simplematch-matching:" + settings.tag());
    target.create(KubernetesDocuments.data("ConfigMap", "matching-session-config", Map.of(
        "trading_session_id", settings.day() + "-regular", "trading_day", settings.day().toString(),
        "matching_image_digest", matching)));
    final String password = UUID.randomUUID().toString().replace("-", "");
    final String dsn = "postgres://simplematch:" + password + "@postgres:5432/simplematch";
    target.create(KubernetesDocuments.data("Secret", "simplematch-postgres-secrets", Map.of(
        "postgres_user", "simplematch", "postgres_password", password)));
    for (String owner : java.util.List.of(
        "simplematch-flyway", "account-service", "risk-service")) {
      target.create(KubernetesDocuments.data(
          "Secret", owner + "-secrets", Map.of("postgres_dsn", dsn)));
    }
  }

  private void publishOpenBarriers() throws Exception {
    final var artifact = inputs.input().artifact();
    target.create(KubernetesDocuments.data("ConfigMap", "matching-open-barrier-run", Map.of(
        "TRADING_DAY", settings.day().toString(), "TRADING_SESSION", settings.day() + "-regular",
        "ARTIFACT_SHA256", artifact.identity().contentSha256(),
        "ROUTING_VERSION", artifact.artifact().routingPolicy().algorithmVersion(),
        "MATCHING_IMAGE_DIGEST",
        images.identities().get("simplematch-matching:" + settings.tag()))));
    target.create(new ObjectMapper(new YAMLFactory()).readTree(settings.root().resolve(
        "deploy/k8s/verification/matching-open-barrier-job.yaml").toFile()));
    KubernetesJobs.awaitComplete(target.client(), "matching-open-barrier-fixture", target.run());
  }

  /** Registers through an owned Pod's local REST endpoint and reconciles uncertain writes. */
  void registerRiskConnector() throws Exception {
    final var json = new ObjectMapper();
    final String name = "risk-service-outbox";
    final String config = target.client().read("get", "configmap",
        "risk-service-outbox-connector", "-o", "json")
        .path("data").path("connector.json").asText();
    final var expected = json.readTree(config);
    if (!name.equals(expected.path("name").asText())) {
      throw new IllegalStateException("risk connector name differs from the selected contract");
    }
    final var pod = connectPod();
    final boolean existing = java.util.stream.StreamSupport.stream(json.readTree(
        connectRest(pod, "GET", "/connectors", "").checked()).spliterator(), false)
        .anyMatch(value -> name.equals(value.asText()));
    // An uncertain POST result is resolved by observing the actual configuration, not repeating it.
    final int registrationExit = existing ? 0
        : connectRest(pod, "POST", "/connectors", config).exitCode();
    java.nio.file.Files.write(target.run().evidence().resolve("connector-registration.json"),
        json.writeValueAsBytes(Map.of("transport", "owned Pod local REST", "pod", pod,
            "already_present", existing, "registration_exit", registrationExit)));
    final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    boolean running = false;
    do {
      requireConnectorConfig(pod, name, expected.path("config"));
      final var status = json.readTree(
          connectRest(pod, "GET", "/connectors/" + name + "/status", "").checked());
      java.nio.file.Files.writeString(target.run().evidence().resolve(name + ".json"),
          DiagnosticText.redact(status.toPrettyString()));
      running = "RUNNING".equals(status.path("connector").path("state").asText())
          && !status.path("tasks").isEmpty()
          && java.util.stream.StreamSupport.stream(status.path("tasks").spliterator(), false)
              .allMatch(task -> "RUNNING".equals(task.path("state").asText()));
      if (!running) {
        Thread.sleep(Duration.ofSeconds(1));
      }
    } while (!running && System.nanoTime() < deadline);
    if (!running) {
      throw new TimeoutException("connector/tasks did not become RUNNING: " + name);
    }
  }

  private String connectPod() throws Exception {
    for (var pod : target.client().read("get", "pods", "-l",
        "app.kubernetes.io/name=kafka-connect," + OwnedNamespace.OWNER_LABEL + "="
            + target.run().runId(), "-o", "json").path("items")) {
      for (var condition : pod.path("status").path("conditions")) {
        if ("Ready".equals(condition.path("type").asText())
            && "True".equals(condition.path("status").asText())
            && !pod.path("metadata").path("uid").asText().isBlank()) {
          return pod.path("metadata").path("name").asText();
        }
      }
    }
    throw new IllegalStateException("no owned Ready Connect Pod is available");
  }

  private BoundedCommand.Result connectRest(String pod, String method, String path, String body)
      throws Exception {
    final var args = new java.util.ArrayList<>(java.util.List.of("exec", "-i", pod, "--", "curl",
        "--fail-with-body", "--silent", "--show-error", "--connect-timeout", "5",
        "--max-time", "45", "--request", method, "--header", "Content-Type: application/json"));
    if ("POST".equals(method)) {
      args.addAll(java.util.List.of("--data-binary", "@-"));
    } else {
      args.addAll(java.util.List.of(
          "--retry", "2", "--retry-delay", "1", "--retry-max-time", "10"));
    }
    args.add("http://localhost:8083" + path);
    return target.client().execute(body, Duration.ofSeconds(65), args.toArray(String[]::new));
  }

  private void requireConnectorConfig(String pod, String name, JsonNode expected) throws Exception {
    final var actual = new ObjectMapper().readTree(
        connectRest(pod, "GET", "/connectors/" + name + "/config", "").checked());
    for (var entry : expected.properties()) {
      if (!actual.has(entry.getKey())
          || !entry.getValue().asText().equals(actual.path(entry.getKey()).asText())) {
        throw new IllegalStateException(
            "existing connector configuration differs: " + entry.getKey());
      }
    }
  }

  private void createNamed(String name) throws Exception {
    for (var document : inputs.profile().documents()) {
      if (name.equals(document.path("metadata").path("name").asText())
          && Set.of("Deployment", "StatefulSet", "Job").contains(document.path("kind").asText())) {
        target.create(document);
      }
    }
  }

}
