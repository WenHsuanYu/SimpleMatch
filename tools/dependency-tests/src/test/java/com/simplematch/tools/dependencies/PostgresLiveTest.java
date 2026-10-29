package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Preserves explicit external primary, TLS, WAL, schema, migration and plan observations. */
class PostgresLiveTest {
  @Test
  void externalPrimaryUsesTlsLogicalWalAndValidServiceLocalSchemas() throws Exception {
    final var source = PostgresConnections.live();
    try (var connection = source.getConnection()) {
      assertTrue(PostgresContracts.bool(connection, "SELECT NOT pg_is_in_recovery()"));
      assertTrue(
          PostgresContracts.bool(connection, "SELECT current_setting('wal_level') = 'logical'"));
      assertTrue(
          PostgresContracts.bool(
              connection, "SELECT current_setting('transaction_read_only') = 'on'"));
      assertTrue(
          PostgresContracts.bool(
              connection,
              "SELECT COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()),"
                  + " false)"));
      for (String owner : FlywayOwners.configurations().keySet()) {
        PostgresContracts.tables(connection, owner);
        final var flyway = FlywayOwners.connect(owner, source);
        flyway.info();
        flyway.validate();
        PostgresContracts.plans(connection, owner);
      }
    }
  }
}
