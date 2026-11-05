package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Records deterministic build inputs without caching or reusing a live verification verdict. */
public final class KubernetesBuildInputs {
  private KubernetesBuildInputs() {}

  /** Checks prerequisites before image builds, then binds completed images to unchanged inputs. */
  public static void main(String[] arguments) throws Exception {
    final var settings = KubernetesSettings.selected();
    final var observed = KubernetesPrerequisites.observe(settings);
    final Path receipt = Path.of(System.getProperty("kubernetesBuildReceipt")).toAbsolutePath();
    final var json = new ObjectMapper();
    if (arguments.length != 1) {
      throw new IllegalArgumentException("expected begin, pull or finish build-input operation");
    }
    switch (arguments[0]) {
      case "begin" -> begin(observed, receipt, json);
      case "pull" -> pull(settings, observed);
      case "finish" -> finish(settings, observed, receipt, json);
      default -> throw new IllegalArgumentException(
          "expected begin, pull or finish build-input operation");
    }
  }

  private static void pull(KubernetesSettings settings, KubernetesPrerequisites inputs)
      throws Exception {
    final var commands = settings.dockerContext().commands(settings.root());
    inputs.docker().requireCurrent(commands);
    for (String reference : inputs.profile().platformImages()) {
      commands.run(settings.dockerContext().command("pull", "--platform", "linux/amd64", reference),
          "", java.time.Duration.ofMinutes(5)).checked();
    }
  }

  private static void begin(KubernetesPrerequisites observed, Path receipt, ObjectMapper json)
      throws Exception {
    final Path parent = receipt.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("build input receipt must name a file");
    }
    Files.createDirectories(parent);
    Files.write(receipt, json.writeValueAsBytes(Map.of("source_fingerprint",
        observed.sourceFingerprint(), "daemon_id", observed.daemonId(),
        "docker_context", observed.docker().context().name(),
        "host_memory_availability_override",
        Boolean.getBoolean("kubernetesIgnoreHostMemoryAvailability"),
        "scenario", observed.budget().path("profile").asText(), "stage", "BUILDING")),
        StandardOpenOption.CREATE_NEW);
  }

  private static void finish(KubernetesSettings settings, KubernetesPrerequisites observed,
      Path receipt, ObjectMapper json) throws Exception {
    final var before = json.readTree(receipt.toFile());
    requireIdentity(before, observed);
    if (!"BUILDING".equals(before.path("stage").asText())) {
      throw new IllegalStateException("runtime source or Docker changed during image building");
    }
    final var images = KubernetesImages.observe(settings, observed.profile());
    Files.write(receipt, json.writeValueAsBytes(Map.of("source_fingerprint",
        observed.sourceFingerprint(), "daemon_id", observed.daemonId(),
        "docker_context", observed.docker().context().name(),
        "host_memory_availability_override",
        Boolean.getBoolean("kubernetesIgnoreHostMemoryAvailability"),
        "scenario", observed.budget().path("profile").asText(), "stage", "IMAGES_BUILT",
        "images", images.identities())));
  }

  static void requireCurrent(KubernetesPrerequisites inputs, KubernetesImages images)
      throws Exception {
    final String selected = System.getProperty("kubernetesBuildReceipt");
    if (selected == null) {
      throw new IllegalArgumentException("use verifyKubernetes to build and bind current images");
    }
    final var json = new ObjectMapper();
    final var receipt = json.readTree(Path.of(selected).toFile());
    requireIdentity(receipt, inputs);
    if (!"IMAGES_BUILT".equals(receipt.path("stage").asText())
        || !json.valueToTree(images.identities()).equals(receipt.path("images"))) {
      throw new IllegalStateException(
          "current source, daemon or image identities differ from the build");
    }
  }

  private static void requireIdentity(JsonNode receipt, KubernetesPrerequisites inputs) {
    if (receipt.path("host_memory_availability_override").asBoolean()
        != Boolean.getBoolean("kubernetesIgnoreHostMemoryAvailability")) {
      throw new IllegalStateException("build receipt capacity policy differs from current run");
    }
    final var expected = Map.of("source_fingerprint", inputs.sourceFingerprint(),
        "scenario", inputs.budget().path("profile").asText(), "daemon_id", inputs.daemonId(),
        "docker_context", inputs.docker().context().name());
    for (var field : expected.entrySet()) {
      if (!field.getValue().equals(receipt.path(field.getKey()).textValue())) {
        throw new IllegalStateException("build receipt identity differs: " + field.getKey());
      }
    }
  }

}
