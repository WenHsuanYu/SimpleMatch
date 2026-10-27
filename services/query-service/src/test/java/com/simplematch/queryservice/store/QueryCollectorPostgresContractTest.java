package com.simplematch.queryservice.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the existing collector contract against the same PostgreSQL fixture as the JDBC tests. */
class QueryCollectorPostgresContractTest {
  @TempDir
  private Path temporaryDirectory;

  @Test
  void collectorSelectsOnlyApprovedFixturesInPostgres() throws Exception {
    final Path repository = Path.of(System.getProperty("simplematch.repositoryRoot"));
    final Path outputFile = temporaryDirectory.resolve("collector.log");
    final ProcessBuilder builder = new ProcessBuilder(
        "python3", "scripts/end-to-end/query-service/tests/collector-postgres-contract.py")
        .directory(repository.toFile())
        .redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    builder.environment().putAll(QueryProjectionTestDatabase.collectorEnvironment());
    final Process process = builder.start();
    try {
      final boolean finished = process.waitFor(2, TimeUnit.MINUTES);
      final String output = Files.readString(outputFile);
      System.out.print(output);
      assertTrue(finished, "Python collector contract exceeded two minutes: " + output);
      assertEquals(0, process.exitValue(), output);
    } finally {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }
  }
}
