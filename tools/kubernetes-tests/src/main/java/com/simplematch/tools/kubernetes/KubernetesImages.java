package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Binds loaded and executed content; identity-only inputs are not imported into nodes. */
final class KubernetesImages {
  private final BoundedCommand commands;
  private final DockerContext context;
  private final Map<String, String> identities;
  private final Map<String, String> configs = new LinkedHashMap<>();
  private final ObjectMapper json = new ObjectMapper();

  private KubernetesImages(BoundedCommand commands, DockerContext context,
      Map<String, String> identities) {
    this.commands = commands;
    this.context = context;
    this.identities = new LinkedHashMap<>(identities);
  }

  static KubernetesImages observe(KubernetesSettings settings, RiskCommandProfile profile)
      throws Exception {
    final var commands = settings.dockerContext().commands(settings.root());
    final var identities = new LinkedHashMap<String, String>();
    final var json = new ObjectMapper();
    for (String repository : List.of("simplematch/account-service", "simplematch/risk-service",
        "simplematch/flyway-runner", "simplematch/risk-matching-e2e-verifier",
        "simplematch-matching", "simplematch/kafka-fixture-publisher")) {
      inspect(commands, settings.dockerContext(), json, identities,
          repository + ":" + settings.tag());
    }
    for (String image : profile.platformImages()) {
      inspect(commands, settings.dockerContext(), json, identities, image);
    }
    return new KubernetesImages(commands, settings.dockerContext(), identities);
  }

  private static void inspect(BoundedCommand commands, DockerContext context, ObjectMapper json,
      Map<String, String> identities, String image) throws Exception {
    final var metadata = json.readTree(commands.run(context.command("image", "inspect", image), "",
        Duration.ofSeconds(30)).checked()).get(0);
    if (!"amd64".equals(metadata.path("Architecture").asText())
        || !"linux".equals(metadata.path("Os").asText())) {
      throw new IllegalStateException("image is not linux/amd64: " + image);
    }
    identities.put(image, metadata.path("Id").asText());
  }

  KubernetesWorkloadImages loadAndProbe(KindEnvironment kind, VerificationContext run)
      throws Exception {
    final var client = kind.client();
    KindHealth.requireImageHeadroom(commands, context, client, run.evidence(), "before-import");
    final String helper = observeHelper(kind);
    importImages(kind, run);
    Files.write(run.evidence().resolve("helper-image-identity.json"), json.writeValueAsBytes(
        Map.of("reference", helper, "host_index_id", identities.get(helper),
            "platform", "linux/amd64", "platform_config_id", configs.get(helper))));
    int number = 0;
    for (var worker : client.read("get", "nodes", "-l",
        "simplematch.io/node-pool=local-resilience", "-o", "json").path("items")) {
      final String node = worker.path("metadata").path("name").asText();
      for (var image : identities.entrySet()) {
        if (!image.getKey().startsWith("simplematch-matching:")) {
          requireNodeIdentity(node, image.getKey());
          probe(kind, run, node, image.getKey(), "image-probe-" + number++);
        }
      }
    }
    Files.write(run.evidence().resolve("image-identities.json"),
        json.writeValueAsBytes(identities));
    Files.write(run.evidence().resolve("platform-config-identities.json"),
        json.writeValueAsBytes(configs));
    KindHealth.requireImageHeadroom(commands, context, client, run.evidence(), "after-import");
    KindHealth.requireStable(client, run.evidence(), "after-import");
    return new KubernetesWorkloadImages(commands, context, kind.state().nodeIds(), configs);
  }

  private String observeHelper(KindEnvironment kind) throws Exception {
    final var config = kind.client().read("get", "configmap", "local-path-config",
        "-n", "local-path-storage", "-o", "json");
    final var helper = new ObjectMapper(new YAMLFactory()).readTree(
        config.path("data").path("helperPod.yaml").asText());
    final String reference = helper.path("spec").path("containers").get(0).path("image").asText();
    commands.run(context.command("pull", "--platform", "linux/amd64", reference), "",
        Duration.ofMinutes(5)).checked();
    inspect(commands, context, json, identities, reference);
    return reference;
  }

  private void importImages(KindEnvironment kind, VerificationContext run) throws Exception {
    for (var image : identities.entrySet()) {
      if (image.getKey().startsWith("simplematch-matching:")) {
        continue;
      }
      final var archive = kind.temporaryImageArchive();
      try (var exporter = new KubernetesImageArchive(commands, context, archive)) {
        configs.put(image.getKey(), exporter.save(image.getKey(), image.getValue()));
        final var imported = commands.run(List.of("kind", "load", "image-archive",
            archive.toString(), "--name", kind.cluster()), "", Duration.ofMinutes(3));
        Files.writeString(run.evidence().resolve("image-import.log"),
            "image=" + image.getKey() + " config=" + configs.get(image.getKey()) + "\n"
                + DiagnosticText.redact(imported.output() + imported.error()),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        imported.checked();
      }
    }
  }

  private void probe(KindEnvironment kind, VerificationContext run, String node, String image,
      String name) throws Exception {
    // CNB's direct strategy avoids requiring Bash in the tiny Spring run images.
    final var command = image.startsWith("simplematch/account-service:")
        || image.startsWith("simplematch/risk-service:")
        ? List.of("/cnb/lifecycle/launcher", "--", "java", "-version")
        : List.of("/bin/sh", "-c", "true");
    final var pod = json.valueToTree(Map.of("apiVersion", "v1", "kind", "Pod", "metadata",
        Map.of("name", name), "spec", Map.of("nodeName", node, "restartPolicy", "Never",
            "activeDeadlineSeconds", 120, "automountServiceAccountToken", false,
            "containers", List.of(Map.of("name", "probe", "image", image,
                "imagePullPolicy", "Never", "command", command)))));
    kind.client().create(KubernetesDocuments.relocate(
        pod, kind.namespace(), run.runId(), Map.of()));
    final long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
    do {
      final var current = kind.client().read("get", "pod", name, "-o", "json");
      final String phase = current.path("status").path("phase").asText();
      if ("Succeeded".equals(phase)) {
        return;
      }
      if ("Failed".equals(phase)) {
        throw new IllegalStateException("image execution probe failed: " + image + " on " + node
            + ": " + DiagnosticText.redact(current.path("status").toString()));
      }
      Thread.sleep(Duration.ofMillis(500));
    } while (System.nanoTime() < deadline);
    throw new TimeoutException("image execution probe did not complete: " + image + " on " + node);
  }

  private void requireNodeIdentity(String node, String reference) throws Exception {
    final var image = json.readTree(commands.run(context.command("exec", node, "crictl", "inspecti",
        reference), "", Duration.ofSeconds(30)).checked());
    final String config = image.path("status").path("id").asText();
    if (!config.matches("sha256:[0-9a-f]{64}") || !config.equals(configs.get(reference))) {
      throw new IllegalStateException(
          "node image config differs from exported content: " + reference);
    }
  }

  Map<String, String> identities() {
    return Map.copyOf(identities);
  }

  Map<String, String> replacements(String tag) {
    final var result = new LinkedHashMap<String, String>();
    identities.keySet().stream().filter(ref -> !ref.startsWith("simplematch-matching:"))
        .forEach(ref -> result.put(ref, ref));
    for (String repository : List.of("simplematch/account-service", "simplematch/risk-service",
        "simplematch/flyway-runner", "simplematch/risk-matching-e2e-verifier",
        "simplematch/kafka-fixture-publisher")) {
      result.put(repository + ":local", repository + ":" + tag);
    }
    return Map.copyOf(result);
  }
}
