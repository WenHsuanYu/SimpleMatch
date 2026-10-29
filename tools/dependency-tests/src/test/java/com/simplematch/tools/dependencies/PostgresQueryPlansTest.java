package com.simplematch.tools.dependencies;

import org.junit.jupiter.api.Test;

/** Runs the retained read-only plan operator through the same assertions as disposable tests. */
class PostgresQueryPlansTest {
  @Test
  void selectedServiceUsesItsRequiredIndexes() throws Exception {
    final String owner = PostgresConnections.required("SIMPLEMATCH_PLAN_SERVICE");
    if (!PostgresContracts.required().has(owner)) {
      throw new IllegalArgumentException("Unknown Flyway service: " + owner);
    }
    try (var connection = PostgresConnections.plans().getConnection()) {
      PostgresContracts.plans(connection, owner);
    }
  }
}
