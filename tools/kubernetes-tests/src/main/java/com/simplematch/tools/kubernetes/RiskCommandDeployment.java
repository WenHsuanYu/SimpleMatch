package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deploys canonical business dependencies in their explicit migration/topic/barrier order. */
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
    start(null);
  }

  void start(KubernetesFixCredentials credentials) throws Exception {
    if (inputs.profile().runsMatching() && credentials == null) {
      throw new IllegalArgumentException("public FIX deployment requires run-owned credentials");
    }
    for (var document : inputs.profile().documents()) {
      if (!Set.of("Deployment", "StatefulSet", "Job").contains(document.path("kind").asText())) {
        target.create(document);
      }
    }
    configureInputs(credentials);
    for (String name : java.util.List.of("postgres", "kafka")) {
      createNamed(name);
    }
    target.client().waitFor("jsonpath={.status.readyReplicas}=3", "statefulset/kafka");
    target.client().waitFor("jsonpath={.status.readyReplicas}=1", "statefulset/postgres");
    final var jobs = new java.util.ArrayList<>(inputs.profile().migrationJobs());
    jobs.add("kafka-topic-provisioning");
    for (String name : jobs) {
      createNamed(name);
      KubernetesJobs.awaitComplete(target.client(), name, target.run());
    }
    publishOpenBarriers();
    KindHealth.requireImageHeadroom(settings.dockerContext().commands(settings.root()),
        settings.dockerContext(), target.client(), target.run().evidence(), "after-bootstrap");
    KindHealth.requireStable(target.client(), target.run().evidence(), "after-bootstrap");
    startBusinessServices();
  }

  private void startBusinessServices() throws Exception {
    for (String name : java.util.List.of(
        "account-service", "risk-service", "persistence", "kafka-connect")) {
      if ("kafka-connect".equals(name) || inputs.profile().javaServices().contains(name)) {
        startWorkload("deployment", name);
      }
    }
    final var connectors = new KubernetesConnectors(target);
    connectors.register("risk-service-outbox");
    if (inputs.profile().runsMatching()) {
      connectors.register("account-service-outbox");
      startWorkload("statefulset", "matching");
      startWorkload("statefulset", "quickfix-gateway");
    }
  }

  private void startWorkload(String kind, String name) throws Exception {
    createNamed(name);
    target.client().execute("", Duration.ofMinutes(11), "rollout", "status",
        kind + "/" + name, "--timeout=600s").checked();
  }

  private void configureInputs(KubernetesFixCredentials credentials) throws Exception {
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
    final var databaseOwners = new java.util.TreeSet<>(inputs.profile().javaServices());
    databaseOwners.add("simplematch-flyway");
    for (String owner : databaseOwners) {
      if ("quickfix-gateway".equals(owner)) {
        target.create(credentials.gatewaySecret(dsn));
        target.create(credentials.tlsSecret());
      } else {
        target.create(KubernetesDocuments.data(
            "Secret", owner + "-secrets", Map.of("postgres_dsn", dsn)));
      }
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

  private void createNamed(String name) throws Exception {
    for (var document : inputs.profile().documents()) {
      if (name.equals(document.path("metadata").path("name").asText())
          && Set.of("Deployment", "StatefulSet", "Job").contains(document.path("kind").asText())) {
        target.create(document);
        return;
      }
    }
    throw new IllegalStateException("selected deployment resource is missing: " + name);
  }

}
