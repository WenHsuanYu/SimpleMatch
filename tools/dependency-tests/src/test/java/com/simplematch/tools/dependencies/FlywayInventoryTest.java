package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that the actual configured owners match the retained schema contracts. */
class FlywayInventoryTest {
  @TempDir private Path directory;

  @Test
  void configuredOwnersMatchRequiredServiceSchemas() throws Exception {
    final var contracts = PostgresContracts.required();
    final var expected = new TreeSet<String>();
    contracts.fieldNames().forEachRemaining(expected::add);
    final var owners = FlywayOwners.configurations();
    assertEquals(expected, owners.keySet());
    for (String owner : expected) {
      assertEquals(owner.replace('-', '_'), owners.get(owner).get("flyway.defaultSchema"));
      assertEquals("false", owners.get(owner).get("flyway.baselineOnMigrate"));
      assertEquals("true", owners.get(owner).get("flyway.cleanDisabled"));
    }
  }

  @Test
  void retainedOperatorInventoryMatchesConfiguredOwnersAndStableAliases() throws Exception {
    final Map<String, String> prefixes =
        Map.of(
            "account-service",
            "accountService",
            "market-data-projection",
            "marketDataProjection",
            "persistence",
            "persistence",
            "query-service",
            "queryService",
            "quickfix-gateway",
            "quickFixGateway",
            "risk-service",
            "riskService");
    assertEquals(
        String.join("\n", new TreeSet<>(prefixes.keySet())),
        invoke("flyway_known_services", "").text());
    for (var entry : FlywayOwners.configurations().entrySet()) {
      final String owner = entry.getKey();
      assertEquals(0, invoke("flyway_service_exists", owner).status());
      assertEquals(":services:" + owner, invoke("flyway_service_project_path", owner).text());
      assertEquals(prefixes.get(owner), invoke("flyway_service_task_prefix", owner).text());
      assertEquals(
          owner.replace('-', '_').toUpperCase(java.util.Locale.ROOT),
          invoke("flyway_service_env_prefix", owner).text());
      assertEquals(
          entry.getValue().get("flyway.defaultSchema"),
          invoke("flyway_service_schema", owner).text());
      final String location =
          entry.getValue().get("flyway.locations").replaceFirst("^filesystem:", "");
      assertEquals(
          FlywayOwners.root().relativize(Path.of(location)).toString(),
          invoke("flyway_service_migration_dir", owner).text());
      final var tables = new java.util.ArrayList<String>();
      PostgresContracts.required()
          .get(owner)
          .get("tables")
          .forEach(table -> tables.add(table.asText()));
      assertEquals(String.join("\n", tables), invoke("flyway_service_smoke_tables", owner).text());
    }
    assertNotEquals(0, invoke("flyway_service_exists", "unknown-service").status());
  }

  private InventoryResult invoke(String function, String owner) throws Exception {
    final Path output = Files.createTempFile(directory, "inventory-", ".log");
    final Process process =
        new ProcessBuilder(
                "bash",
                "-c",
                "source \"$1\"; \"$2\" \"$3\"",
                "inventory",
                FlywayOwners.root().resolve("scripts/lib/flyway-services.sh").toString(),
                function,
                owner)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertTrue(Files.size(output) < 8192);
      return new InventoryResult(process.exitValue(), Files.readString(output).strip());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private record InventoryResult(int status, String text) {}
}
