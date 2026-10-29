package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;

/** Executes the retained database contracts through JDBC rather than command-output matching. */
final class PostgresContracts {
  private PostgresContracts() {}

  static JsonNode required() throws IOException {
    try (var source = PostgresContracts.class.getResourceAsStream("/postgres-contracts.json")) {
      return new ObjectMapper().readTree(source);
    }
  }

  static void tables(Connection connection, String owner) throws Exception {
    final String schema = FlywayOwners.configurations().get(owner).get("flyway.defaultSchema");
    for (JsonNode table : required().get(owner).get("tables")) {
      assertTrue(
          bool(
              connection,
              "SELECT to_regclass('" + schema + "." + table.asText() + "') IS NOT NULL"),
          "Missing relation " + schema + "." + table.asText());
    }
    assertTrue(
        bool(
            connection,
            "SELECT count(*) > 0 FROM " + schema + ".flyway_schema_history WHERE success"));
    assertFalse(bool(connection, "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL"));
  }

  static void plans(Connection connection, String owner) throws Exception {
    final JsonNode contract = required().get(owner);
    if (contract == null) {
      throw new IllegalArgumentException("Unknown Flyway service: " + owner);
    }
    final String schema = FlywayOwners.configurations().get(owner).get("flyway.defaultSchema");
    for (JsonNode plan : contract.get("plans")) {
      try (var query =
          connection.prepareStatement(
              "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = ? AND indexname = ?)")) {
        query.setString(1, schema);
        query.setString(2, plan.get("index").asText());
        try (var result = query.executeQuery()) {
          assertTrue(result.next() && result.getBoolean(1), "Missing index " + plan.get("index"));
        }
      }
      try (var statement = connection.createStatement()) {
        statement.execute("SET enable_seqscan = off");
        final StringBuilder observed = new StringBuilder();
        try (var result =
            statement.executeQuery("EXPLAIN (COSTS OFF) " + plan.get("sql").asText())) {
          while (result.next()) {
            observed.append(result.getString(1)).append('\n');
          }
        }
        assertTrue(
            observed.toString().contains(plan.get("index").asText())
                || (plan.has("alternate")
                    && observed.toString().contains(plan.get("alternate").asText())),
            () -> plan.get("description").asText() + ":\n" + observed);
      }
    }
  }

  static void constraints(Connection connection) throws IOException, SQLException {
    final String sql =
        Files.readString(FlywayOwners.root().resolve("scripts/check-service-schema-contracts.sql"))
            .replace("\\set ON_ERROR_STOP on", "");
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static void gatewayIdentity(Connection connection) throws Exception {
    final var directory = FlywayOwners.root().resolve("scripts/end-to-end/critical-consumers");
    try (var statement = connection.createStatement()) {
      statement.execute(
          Files.readString(directory.resolve("tests/fixtures/gateway-session-padding.sql")));
      try {
        try (var result =
            statement.executeQuery(
                Files.readString(directory.resolve("sql/gateway-session-state.sql")))) {
          assertTrue(result.next());
          final var mapper = new ObjectMapper();
          assertEquals(
              mapper.readTree(
                  directory.resolve("tests/baselines/gateway-session-identity.json").toFile()),
              mapper.readTree(result.getString(1)).get("identity"));
        }
      } finally {
        statement.execute("ROLLBACK");
      }
    }
  }

  static boolean bool(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getBoolean(1);
    }
  }
}
