package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.exception.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Captures observed identities and writes success only after exact owned-resource cleanup. */
final class CdcRunEvidence {
  private final ObjectNode report;
  private final List<String> containers = new ArrayList<>();
  private final String network;

  CdcRunEvidence(CdcEnvironment environment) throws Exception {
    final var mapper = new ObjectMapper();
    report =
        mapper
            .createObjectNode()
            .put("schema_version", 1)
            .put("run_id", environment.runId)
            .put("replication_factor", environment.replication)
            .put("source_revision", git("rev-parse", "HEAD"))
            .put("worktree_dirty", !git("status", "--porcelain").isEmpty())
            .put("source_input_sha256", fingerprint())
            .put("observed_at", Instant.now().toString());
    network = environment.network.getId();
    report.put("network_id", network);
    final var images = report.putArray("containers");
    final var owned = new ArrayList<org.testcontainers.containers.GenericContainer<?>>();
    owned.add(environment.postgres);
    owned.addAll(environment.brokers);
    owned.add(environment.worker);
    for (var container : owned) {
      final var observed =
          container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec();
      containers.add(observed.getId());
      final var image =
          images
              .addObject()
              .put("container_id", observed.getId())
              .put("image_reference", observed.getConfig().getImage())
              .put("image_config_id", observed.getImageId());
      final var digests = image.putArray("repository_digests");
      final var actual =
          container
              .getDockerClient()
              .inspectImageCmd(observed.getImageId())
              .exec()
              .getRepoDigests();
      if (actual != null) {
        actual.forEach(digests::add);
      }
    }
  }

  void complete(CdcEnvironment environment, KafkaPublication kafka) throws Exception {
    final var docker = environment.postgres.getDockerClient();
    for (String id : containers) {
      assertThrows(
          NotFoundException.class,
          () -> docker.inspectContainerCmd(id).exec(),
          "Owned container survived cleanup");
    }
    assertThrows(
        NotFoundException.class,
        () -> docker.inspectNetworkCmd().withNetworkId(network).exec(),
        "Owned network survived cleanup");
    report.put("cleanup", "COMPLETE").put("status", "PASS");
    final var events = report.putArray("publications");
    kafka.publications().forEach(events::add);
    final Path destination =
        FlywayOwners.root()
            .resolve(
                "tools/dependency-tests/build/reports/dependency-tests/cdc/" + environment.runId);
    Files.createDirectories(destination);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(destination.resolve("report.json").toFile(), report);
    System.out.println(
        "CDC run "
            + environment.runId
            + " RF"
            + environment.replication
            + " passed with verified cleanup");
  }

  private String fingerprint() throws Exception {
    final Path root = FlywayOwners.root();
    final var paths = new TreeSet<Path>();
    for (String name :
        List.of(
            "tools/dependency-tests/src",
            "tools/recovery-evidence-verifier/src/main",
            "deploy/compose/risk-service-outbox-connector.json",
            "deploy/compose/account-service-outbox-connector.json",
            "tools/dependency-tests/build.gradle.kts",
            "tools/dependency-tests/gradle.lockfile",
            "tools/recovery-evidence-verifier/build.gradle.kts",
            "tools/recovery-evidence-verifier/gradle.lockfile",
            "gradle/libs.versions.toml")) {
      try (var files = Files.walk(root.resolve(name))) {
        files.filter(Files::isRegularFile).forEach(paths::add);
      }
    }
    for (CdcOwner owner : CdcOwner.values()) {
      for (String location :
          FlywayOwners.configurations().get(owner.service).get("flyway.locations").split(",")) {
        try (var files = Files.walk(Path.of(location.replaceFirst("^filesystem:", "")))) {
          files.filter(Files::isRegularFile).forEach(paths::add);
        }
      }
    }
    final var digest = MessageDigest.getInstance("SHA-256");
    for (Path path : paths) {
      digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(Files.readAllBytes(path));
      digest.update((byte) 0);
    }
    digest.update(System.getProperty("dependencyFlywayOwners").getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private String git(String... arguments) throws Exception {
    final var command = new ArrayList<>(List.of("git", "-C", FlywayOwners.root().toString()));
    command.addAll(List.of(arguments));
    final Process process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("Could not identify source checkout");
      }
      return new String(process.getInputStream().readNBytes(32768), StandardCharsets.UTF_8).strip();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
      }
    }
  }
}
