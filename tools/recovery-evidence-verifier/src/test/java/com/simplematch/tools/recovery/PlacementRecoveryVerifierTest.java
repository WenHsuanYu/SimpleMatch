package com.simplematch.tools.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlacementRecoveryVerifierTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void publishesOnlyObservedPlacementAndServing() throws Exception {
    fixture();
    final Path report = new PlacementReportWriter().write(directory, 60);
    assertEquals("local/query-service/redis-outage", new PlacementRecoveryVerifier().verify(report));
    final ObjectNode content = (ObjectNode) mapper.readTree(report.toFile());
    content.put("claim_boundary", "arbitrary caller prose");
    mapper.writeValue(report.toFile(), content);
    assertEquals("local/query-service/redis-outage", new PlacementRecoveryVerifier().verify(report));
  }

  @ParameterizedTest
  @MethodSource("insufficientFacts")
  void refusesToPublishWhenRecoveryFactsAreInsufficient(Change change) throws Exception {
    fixture();
    replace(change.file(), change.pointer(), change.json());
    assertThrows(IllegalArgumentException.class, () -> new PlacementReportWriter().write(directory, 60));
    assertFalse(Files.exists(directory.resolve("java-placement-serving.json")));
  }

  static Stream<Change> insufficientFacts() {
    return Stream.of(
        new Change("placement/baseline.json", "/pods/1/node", "\"simplematch-live-worker\""),
        new Change("placement/baseline.json", "/pods/0/ready", "false"),
        new Change("placement/baseline.json", "/pods/0/image_id", "\"image:mutable\""),
        new Change("placement/redis-outage.json", "/pods/0/uid", "\"replacement\""),
        new Change("placement/redis-outage.json", "/pods/0/restart_count", "1"),
        new Change("placement/redis-outage.json", "/deployment_image", "\"other:mutable\""),
        new Change("placement/redis-during.json", "/desired_replicas", "1"),
        new Change("placement/redis-after.json", "/pods", "[]"),
        new Change("health/redis-outage.json", "/liveness/http_status", "503"),
        new Change("health/baseline.json", "/serving/authentication", "\"anonymous-health\""));
  }

  @Test
  void rejectsChangedFilesAndOldReports() throws Exception {
    fixture();
    final Path report = new PlacementReportWriter().write(directory, 60);
    replace("placement/baseline.json", "/pods/0/uid", "\"changed-after-publication\"");
    assertThrows(IllegalArgumentException.class, () -> new PlacementRecoveryVerifier().verify(report));
    replace("java-placement-serving.json", "/schema_version", "2");
    assertThrows(IllegalArgumentException.class, () -> new PlacementRecoveryVerifier().verify(report));
  }

  @Test
  void rejectsEvidenceOutsideTheOwnedDirectory() throws Exception {
    fixture();
    final Path report = new PlacementReportWriter().write(directory, 60);
    final Path outside = Files.createTempFile("placement-outside", ".json");
    try {
      Files.delete(directory.resolve("placement/baseline.json"));
      Files.createSymbolicLink(directory.resolve("placement/baseline.json"), outside);
      assertThrows(IllegalArgumentException.class, () -> new PlacementRecoveryVerifier().verify(report));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void observesTheActualProbeWindowBeforeAcceptingThePlannedOutage() throws Exception {
    fixture();
    final Path deployment = directory.resolve("deployment.json");
    final Path pods = directory.resolve("pods.json");
    final Path nodes = directory.resolve("nodes.json");
    assertTrue(new PlacementSnapshots().normalize(deployment, pods, nodes, 60).isObject());
    replace("deployment.json",
        "/spec/template/spec/containers/0/livenessProbe/periodSeconds", "20");
    assertThrows(IllegalArgumentException.class,
        () -> new PlacementSnapshots().normalize(deployment, pods, nodes, 60));
    assertTrue(new PlacementSnapshots().normalize(deployment, pods, nodes, 100).isObject());
  }

  @Test
  void rejectsAnOutageShorterThanTheLivenessWindow() throws Exception {
    fixture();
    assertThrows(IllegalArgumentException.class,
        () -> new PlacementReportWriter().write(directory, 59));
  }

  @Test
  void commandLineEvaluatesThePublishedReport() throws Exception {
    fixture();
    final Path report = new PlacementReportWriter().write(directory, 60);
    final CommandResult result = CommandResult.verifier(directory.resolve("command.txt"),
        "placement", "verify", report.toString());
    assertEquals(0, result.exitCode(), result.output());
    assertEquals("local/query-service/redis-outage", result.output().strip());
  }

  @Test
  void collectorCommandChecksRedisBeforeTheFault() throws Exception {
    fixture();
    final String snapshot = directory.resolve("placement/redis-before.json").toString();
    final CommandResult accepted = CommandResult.verifier(directory.resolve("redis-before.txt"),
        "placement", "redis", snapshot, "1");
    assertEquals(0, accepted.exitCode(), accepted.output());
    final CommandResult rejected = CommandResult.verifier(directory.resolve("redis-wrong.txt"),
        "placement", "redis", snapshot, "0");
    assertEquals(1, rejected.exitCode(), rejected.output());
  }

  @ParameterizedTest
  @ValueSource(strings = {"readiness", "liveness", "serving"})
  void rejectsAHealthBodyThatDoesNotProveTheRequestedObservation(String name) throws Exception {
    final Path body = directory.resolve("body.json");
    Files.writeString(body, "{\"status\":\"DOWN\"}");
    assertThrows(IllegalArgumentException.class,
        () -> PlacementHealth.observation("baseline", name, 200, body));
  }

  @Test
  void rejectsSymlinkedEvidenceBeforePreparingJavaOrAccessingTheCluster() throws Exception {
    final Path alias = directory.resolve("alias");
    Files.createSymbolicLink(alias, directory);
    final CommandResult result = runner(alias.resolve("new-run"), directory);
    assertEquals(1, result.exitCode(), result.output());
    assertTrue(result.output().contains("path contains a symlink component"), result.output());
    assertFalse(Files.exists(directory.resolve("new-run")));
  }

  @Test
  void rejectsIncompleteRetainedPassBeforePreparingJavaOrAccessingTheCluster() throws Exception {
    final Path retained = Files.createDirectory(directory.resolve("retained"));
    Files.writeString(retained.resolve("report.md"), "- status: PASSED\n");
    final CommandResult result = runner(directory.resolve("new-run"), retained);
    assertEquals(1, result.exitCode(), result.output());
    assertTrue(result.output().contains("evidence manifest is missing"), result.output());
  }

  private CommandResult runner(Path evidence, Path retained) throws Exception {
    final Path root = Path.of(System.getProperty("recoveryRepoRoot"));
    final Path commands = Files.createDirectory(directory.resolve("commands"));
    final Path docker = commands.resolve("docker");
    final Path events = directory.resolve("docker-events");
    Files.writeString(docker,
        "#!/bin/sh\nprintf docker >> \"$RECOVERY_TEST_DOCKER_EVENTS\"\nexit 17\n");
    assertTrue(docker.toFile().setExecutable(true));
    final CommandResult result = CommandResult.run(directory.resolve("runner.txt"), List.of("bash",
        root.resolve("scripts/run-local-java-placement-serving-check.sh").toString(),
        "--namespace", "simplematch-java-run", "--namespace-run-id", "run-1",
        "--retained-evidence-dir", retained.toString(), "--evidence-dir", evidence.toString()),
        Map.of("PATH", commands + ":" + System.getenv("PATH"),
            "RECOVERY_TEST_DOCKER_EVENTS", events.toString()));
    assertFalse(Files.exists(events), result.output());
    return result;
  }

  private void fixture() throws Exception {
    final Path source = Path.of(Objects.requireNonNull(getClass().getResource("/placement")).toURI());
    try (var files = Files.walk(source)) {
      for (Path path : files.toList()) {
        final Path target = directory.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.copy(path, target);
        }
      }
    }
  }

  private void replace(String file, String pointer, String json) throws Exception {
    final Path target = directory.resolve(file);
    final ObjectNode root = (ObjectNode) mapper.readTree(target.toFile());
    final int split = pointer.lastIndexOf('/');
    ((ObjectNode) root.at(pointer.substring(0, split)))
        .set(pointer.substring(split + 1), mapper.readTree(json));
    mapper.writeValue(target.toFile(), root);
  }

  private record Change(String file, String pointer, String json) {}
}
