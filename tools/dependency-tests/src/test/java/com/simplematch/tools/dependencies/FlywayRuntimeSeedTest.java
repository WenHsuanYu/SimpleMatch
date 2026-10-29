package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real operational entry point before workspace preparation or Gradle execution. */
class FlywayRuntimeSeedTest {
  @TempDir private Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingOrWrapperOnlySeedFailsBeforeWorkspaceCreation(boolean wrapperOnly) throws Exception {
    final Path seed = directory.resolve("seed");
    if (wrapperOnly) {
      Files.createDirectories(seed.resolve("wrapper/dists"));
    }
    final Path workspace = directory.resolve("workspace");
    final Path log = directory.resolve("runner.log");
    final ProcessBuilder command =
        new ProcessBuilder(
                "bash", FlywayOwners.root().resolve("deploy/docker/run-flyway").toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    command
        .environment()
        .putAll(
            java.util.Map.of(
                "SIMPLEMATCH_FLYWAY_SERVICE_ID", "account-service",
                "SIMPLEMATCH_GRADLE_CACHE_SEED_DIR", seed.toString(),
                "SIMPLEMATCH_FLYWAY_WORK_DIR", workspace.toString(),
                "GRADLE_USER_HOME", directory.resolve("gradle").toString()));
    final Process process = command.start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Seed validation exceeded its deadline");
      assertNotEquals(0, process.exitValue());
      assertTrue(
          Files.readString(log)
              .contains("Flyway runtime dependency seed is missing or incomplete"));
      assertFalse(Files.exists(workspace));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }
}
