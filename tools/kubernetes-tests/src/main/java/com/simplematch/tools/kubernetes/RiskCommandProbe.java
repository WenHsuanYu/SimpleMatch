package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Runs the existing typed verifier and observes storage facts for Jupiter's cross-layer checks. */
final class RiskCommandProbe {
  private final KubernetesSettings settings;
  private final KubernetesPrerequisites inputs;
  private final KubernetesScenarioTarget target;
  private final ObjectMapper json = new ObjectMapper();

  RiskCommandProbe(KubernetesSettings settings, KubernetesPrerequisites inputs,
      KubernetesScenarioTarget target) {
    this.settings = settings;
    this.inputs = inputs;
    this.target = target;
  }

  RiskCommandEnvironment.Facts execute() throws Exception {
    final UUID accountId = UUID.randomUUID();
    final String postgres = postgresPod();
    final var variables = Map.of("account_id", accountId.toString(), "trading_day",
        settings.day().toString(), "now_ms", Long.toString(System.currentTimeMillis()));
    sql(postgres, "account-buy.sql", variables);
    target.create(KubernetesDocuments.data("ConfigMap", "risk-matching-e2e-run", Map.of(
        "SIMPLEMATCH_RM1_TRADING_DAY", settings.day().toString(),
        "SIMPLEMATCH_RM1_ACCOUNT_ID", accountId.toString(), "SIMPLEMATCH_RM1_SIDE", "BUY",
        "SIMPLEMATCH_RM1_RUN_ID", target.run().runId(), "SIMPLEMATCH_RM1_TIMEOUT_SECONDS", "90")));
    target.create(new ObjectMapper(new YAMLFactory()).readTree(settings.root().resolve(
        "deploy/k8s/verification/risk-matching-e2e-verifier-job.yaml").toFile()));
    final String pod = awaitVerifierPod();
    awaitHandoff(pod);
    final Path evidence = Files.createDirectory(
        target.run().evidence().resolve("verifier"));
    target.client().execute("", Duration.ofMinutes(1), "cp",
        pod + ":/tmp/evidence/.", evidence.toString()).checked();
    target.client().run("", "exec", pod, "--", "touch", "/tmp/evidence/.collected");
    KubernetesJobs.awaitComplete(
        target.client(), "risk-matching-e2e-verifier", target.run());
    if (!"0".equals(Files.readString(evidence.resolve(".exit-code")).trim())) {
      throw new IllegalStateException("typed verifier did not exit successfully");
    }
    final JsonNode request = read(evidence, "request.json");
    final var command = Map.of("command_id", UUID.fromString(
        request.path("commandId").asText()).toString());
    final JsonNode admission = json.readTree(sql(postgres, "admission.sql", command));
    final JsonNode outbox = json.readTree(sql(postgres, "outbox.sql", command));
    Files.write(target.run().evidence().resolve("risk-admission.json"),
        json.writeValueAsBytes(admission));
    Files.write(target.run().evidence().resolve("risk-outbox.json"),
        json.writeValueAsBytes(outbox));
    final var expected = json.valueToTree(Map.of("accountId", accountId.toString(),
        "tradingDay", settings.day().toString(), "runId", target.run().runId(),
        "artifactContentSha256", inputs.input().artifact().identity().contentSha256()));
    return new RiskCommandEnvironment.Facts(expected, read(evidence, "selected-instrument.json"),
        admission, outbox, read(evidence, "verifier-verdict.json"),
        read(evidence, "admission-outcome.json"), request,
        read(evidence, "matching-command-record.json"));
  }

  private String postgresPod() throws Exception {
    final var pods = target.client().read("get", "pods", "-l", "app.kubernetes.io/name=postgres",
        "-o", "json").path("items");
    if (pods.size() != 1) {
      throw new IllegalStateException("PostgreSQL Pod identity is ambiguous or absent");
    }
    return pods.get(0).path("metadata").path("name").asText();
  }

  private String sql(String pod, String resource, Map<String, String> variables) throws Exception {
    final var arguments = new ArrayList<>(List.of("exec", "-i", pod, "--", "psql",
        "--no-psqlrc", "-U", "simplematch", "-d", "simplematch", "-Atq", "-v", "ON_ERROR_STOP=1"));
    variables.forEach((name, value) -> arguments.addAll(List.of("-v", name + "=" + value)));
    final String statement;
    try (var stream = java.util.Objects.requireNonNull(RiskCommandProbe.class
        .getResourceAsStream("/risk-command/" + resource), "missing SQL fixture")) {
      statement = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    return target.client().run(statement, arguments.toArray(String[]::new));
  }

  private String awaitVerifierPod() throws Exception {
    final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    do {
      final var pods = target.client().read("get", "pods", "-l",
          "job-name=risk-matching-e2e-verifier", "-o", "json").path("items");
      if (pods.size() == 1) {
        return pods.get(0).path("metadata").path("name").asText();
      }
      if (pods.size() > 1) {
        throw new IllegalStateException("verifier Pod identity is ambiguous");
      }
      Thread.sleep(Duration.ofSeconds(1));
    } while (System.nanoTime() < deadline);
    throw new TimeoutException("verifier Job did not create its Pod");
  }

  private void awaitHandoff(String pod) throws Exception {
    final long deadline = System.nanoTime() + Duration.ofSeconds(330).toNanos();
    do {
      final var ready = target.client().execute("", Duration.ofSeconds(20), "exec", pod, "--",
          "test", "-f", "/tmp/evidence/.ready");
      if (ready.exitCode() == 0) {
        return;
      }
      final String phase = target.client().read("get", "pod", pod, "-o", "json")
          .path("status").path("phase").asText();
      if (java.util.Set.of("Failed", "Succeeded").contains(phase)) {
        throw new IllegalStateException("verifier became terminal before evidence handoff");
      }
      Thread.sleep(Duration.ofSeconds(2));
    } while (System.nanoTime() < deadline);
    throw new TimeoutException("verifier evidence handoff exceeded its deadline");
  }

  private JsonNode read(Path directory, String name) throws Exception {
    return json.readTree(directory.resolve(name).toFile());
  }
}
