package com.simplematch.tools.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ConnectRecoveryVerifierTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void acceptsReassignmentAndANewPublicationAfterTheFault() throws Exception {
    fixture();
    assertEquals("local/connect-task-reassignment",
        new ConnectRecoveryVerifier().verify(directory.resolve("connect-worker-loss.json")));
    new ConnectPrerequisites().verify(directory);
  }

  @Test
  void permitsANewOwnerPodOnTheOriginalNode() throws Exception {
    fixture();
    replace("connect-pods-after-reassignment.json", "/items/1/spec/nodeName",
        "\"simplematch-live-worker\"");
    replace("task-owner-after-reassignment.json", "/node", "\"simplematch-live-worker\"");
    assertEquals("local/connect-task-reassignment",
        new ConnectRecoveryVerifier().verify(directory.resolve("connect-worker-loss.json")));
  }

  @ParameterizedTest
  @MethodSource("insufficientFacts")
  void rejectsEvidenceThatDoesNotProveReassignmentAndNewPublication(Change change) throws Exception {
    fixture();
    replace(change.file(), change.pointer(), change.json());
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectRecoveryVerifier().verify(directory.resolve("connect-worker-loss.json")));
  }

  static Stream<Change> insufficientFacts() {
    return Stream.of(
        new Change("connect-status-after-reassignment.json", "/tasks", "[]"),
        new Change("connect-status-after-reassignment.json", "/tasks/0/id", "1"),
        new Change("connect-status-after-reassignment.json", "/tasks/0/worker_id",
            "\"10.244.0.11:8083\""),
        new Change("task-owner-after-reassignment.json", "/pod_uid", "\"uid-a\""),
        new Change("connect-status-before-delete.json", "/tasks/0/worker_id",
            "\"10.244.0.22:8083\""),
        new Change("pod-delete-precondition.json", "/metadata/uid", "\"drifted\""),
        new Change("pod-delete-precondition.json", "/metadata/deletionTimestamp", "\"terminating\""),
        new Change("worker-loss.json", "/delete_selector", "\"another-run\""),
        new Change("worker-loss.json", "/delete_requested", "false"),
        new Change("target-delete-observation.json", "/target_uid_absent", "false"),
        new Change("account-transition.json", "/transition_created_at_unix_ms", "1"),
        new Change("account-outbox-baseline.json", "/event_ids",
            "[\"00000000-0000-7000-8000-000000000001\"]"),
        new Change("account-publication.json", "/offset", "1"),
        new Change("account-publication.json", "/event_id", "\"another-event\""),
        new Change("connect-worker-loss.json", "/schema_version", "2"),
        new Change("connect-worker-loss.json", "/status", "\"FAILED\""),
        new Change("provenance.json", "/namespace_run_id", "\"another-run\""));
  }

  @Test
  void rejectsAnEnvironmentThatCannotReassignAServiceOwnedTask() throws Exception {
    fixture();
    replace("connect-pods-before.json", "/items/1/spec/nodeName",
        "\"simplematch-live-worker\"");
    assertThrows(IllegalArgumentException.class, () -> new ConnectPrerequisites().verify(directory));
  }

  @Test
  void rejectsAnIncompleteAccountMigration() throws Exception {
    fixture();
    replace("prerequisites/account-service-flyway.json", "/status/conditions", "[]");
    assertThrows(IllegalArgumentException.class, () -> new ConnectPrerequisites().verify(directory));
  }

  @Test
  void rejectsInternalTopicsWithInsufficientDurability() throws Exception {
    fixture();
    final Path topic = directory.resolve("prerequisites/simplematch-connect-configs.txt");
    Files.writeString(topic, Files.readString(topic).replace("ReplicationFactor: 3",
        "ReplicationFactor: 1"));
    assertThrows(IllegalArgumentException.class, () -> new ConnectPrerequisites().verify(directory));
  }

  @Test
  void resolvesTheOwnerFromTheRunningTaskAndReadyPod() throws Exception {
    fixture();
    final var owner = new ConnectOwners().resolve(
        JsonReports.read(directory.resolve("connect-status-before.json")),
        JsonReports.read(directory.resolve("connect-pods-before.json")));
    assertEquals("kafka-connect-a", owner.pod().name());
    assertEquals("uid-a", owner.pod().uid());
  }

  private void fixture() throws Exception {
    final Path source = Path.of(Objects.requireNonNull(getClass().getResource("/connect")).toURI());
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
