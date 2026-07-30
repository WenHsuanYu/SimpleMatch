package com.simplematch.riskservice.testsupport;

import java.util.UUID;

/** Builds valid, isolated H2 JDBC URLs for risk-service database tests. */
public final class H2TestDatabaseUrl {
  private static final String POSTGRES_MODE_OPTIONS = ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  private static final String RISK_SERVICE_OPTIONS =
      POSTGRES_MODE_OPTIONS
          + ";INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service";

  private H2TestDatabaseUrl() {}

  /**
   * Creates an H2 in-memory URL with a database name accepted by the current H2 driver.
   *
   * @return a unique H2 URL configured for the {@code risk_service} schema
   */
  public static String uniqueRiskServiceUrl() {
    return riskServiceUrl("risk" + UUID.randomUUID().toString().replace("-", ""));
  }

  /**
   * Creates an H2 URL for a named risk-service database.
   *
   * @param databaseName alphanumeric or underscore database name
   * @return an H2 URL configured for the {@code risk_service} schema
   */
  public static String riskServiceUrl(String databaseName) {
    if (databaseName == null || !databaseName.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException(
          "databaseName must contain only letters, digits, or underscores");
    }
    return "jdbc:h2:mem:" + databaseName + RISK_SERVICE_OPTIONS;
  }

  /**
   * Creates an isolated H2 URL in PostgreSQL compatibility mode.
   *
   * @return a unique H2 URL without a preselected schema
   */
  public static String uniquePostgresModeUrl() {
    return "jdbc:h2:mem:risk"
        + UUID.randomUUID().toString().replace("-", "")
        + POSTGRES_MODE_OPTIONS;
  }
}
