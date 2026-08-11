package com.simplematch.config;

/**
 * Service-owned JDBC pool policy applied by the shared managed DataSource adapter.
 *
 * @param schema default database schema for the service
 * @param maximumPoolSize maximum number of pooled connections
 * @param poolName stable pool identity used in diagnostics and metrics
 */
public record SimpleMatchDataSourceSettings(
    String schema, int maximumPoolSize, String poolName) {
  private static final int MINIMUM_POOL_SIZE = 1;

  /** Validates the service-owned settings before Spring creates the pool. */
  public SimpleMatchDataSourceSettings {
    if (schema == null || schema.isBlank()) {
      throw new IllegalArgumentException("DataSource schema must not be blank");
    }
    if (maximumPoolSize < MINIMUM_POOL_SIZE) {
      throw new IllegalArgumentException("DataSource maximumPoolSize must be positive");
    }
    if (poolName == null || poolName.isBlank()) {
      throw new IllegalArgumentException("DataSource poolName must not be blank");
    }
  }
}
