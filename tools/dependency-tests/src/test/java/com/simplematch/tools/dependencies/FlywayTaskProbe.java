package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Executes the actual service-owned Gradle tasks with an isolated project cache and database. */
final class FlywayTaskProbe {
  private FlywayTaskProbe() {}

  static void run(PostgreSQLContainer postgres, Path directory, String... tasks) throws Exception {
    final List<String> arguments =
        new ArrayList<>(
            List.of(
                FlywayOwners.root().resolve("gradlew").toString(),
                "--no-daemon",
                "--offline",
                "--console=plain",
                "--stacktrace",
                "--project-cache-dir",
                directory.resolve("cache").toString()));
    arguments.addAll(List.of(tasks));
    final Path log = Files.createTempFile(directory, "flyway-tasks-", ".log");
    final ProcessBuilder command =
        new ProcessBuilder(arguments)
            .directory(FlywayOwners.root().toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    for (String owner : FlywayOwners.configurations().keySet()) {
      final String prefix = owner.replace('-', '_').toUpperCase(java.util.Locale.ROOT);
      command.environment().remove(prefix + "_FLYWAY_DSN");
      command.environment().put(prefix + "_FLYWAY_JDBC_URL", postgres.getJdbcUrl());
      command.environment().put(prefix + "_FLYWAY_USERNAME", postgres.getUsername());
      command.environment().put(prefix + "_FLYWAY_PASSWORD", postgres.getPassword());
    }
    command.environment().remove("SIMPLEMATCH_POSTGRES_DSN");
    final Process process = command.start();
    try {
      assertTrue(
          process.waitFor(240, TimeUnit.SECONDS), "Service Flyway tasks exceeded their deadline");
      assertEquals(0, process.exitValue(), () -> diagnostics(log));
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static String diagnostics(Path log) {
    try (var input = Files.newInputStream(log)) {
      input.skipNBytes(Math.max(0, Files.size(log) - 16384));
      return new String(input.readNBytes(16384), StandardCharsets.UTF_8);
    } catch (java.io.IOException failure) {
      return "Cannot read Flyway task diagnostics: " + failure.getClass().getSimpleName();
    }
  }
}
