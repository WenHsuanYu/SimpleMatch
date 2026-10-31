package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesSourceTest {
  @TempDir Path repository;

  @Test
  void stagingTheSameFilesDoesNotChangeTheirSourceIdentity() throws Exception {
    final var commands = new BoundedCommand(repository, Map.of());
    commands.run(List.of("git", "init", "--quiet"), "", Duration.ofSeconds(5)).checked();
    final Path runtime = repository.resolve("services/account-service/src/main/java/Service.java");
    final Path scenario = repository.resolve("tools/kubernetes-tests/src/test/java/Scenario.java");
    Files.createDirectories(runtime.getParent());
    Files.createDirectories(scenario.getParent());
    Files.writeString(runtime, "unchanged runtime source");
    Files.writeString(scenario, "unchanged actual assertion");
    commands.run(List.of("git", "add", runtime.toString()), "", Duration.ofSeconds(5)).checked();
    final String before = KubernetesSource.verificationFingerprint(repository, commands);
    commands.run(List.of("git", "add", "."), "", Duration.ofSeconds(5)).checked();
    assertEquals(before, KubernetesSource.verificationFingerprint(repository, commands));
  }

  @Test
  void executedProjectMetadataAndWrapperChangeTheRuntimeImageIdentity() throws Exception {
    final var commands = new BoundedCommand(repository, Map.of());
    commands.run(List.of("git", "init", "--quiet"), "", Duration.ofSeconds(5)).checked();
    for (String name : List.of("tools/kubernetes-tests/build.gradle.kts", "gradle.lockfile",
        "gradlew", "services/query-service/build.gradle.kts", "tools/other/gradle.lockfile")) {
      final Path file = repository.resolve(name);
      Files.createDirectories(file.getParent());
      final String before = KubernetesSource.fingerprint(repository, commands);
      Files.writeString(file, "executed build input");
      assertNotEquals(before, KubernetesSource.fingerprint(repository, commands), name);
    }
  }

  @Test
  void recordsHostScenarioChangesWithoutInvalidatingUnchangedRuntimeBuildInputs() throws Exception {
    final var commands = new BoundedCommand(repository, Map.of());
    commands.run(List.of("git", "init", "--quiet"), "", Duration.ofSeconds(5)).checked();
    final Path scenario = repository.resolve("tools/kubernetes-tests/src/test/java/Scenario.java");
    Files.createDirectories(scenario.getParent());
    Files.writeString(scenario, "initial scenario");
    final String runtime = KubernetesSource.fingerprint(repository, commands);
    final String verification = KubernetesSource.verificationFingerprint(repository, commands);
    Files.writeString(scenario, "changed actual assertion");
    assertEquals(runtime, KubernetesSource.fingerprint(repository, commands));
    assertNotEquals(verification, KubernetesSource.verificationFingerprint(repository, commands));
  }
}
