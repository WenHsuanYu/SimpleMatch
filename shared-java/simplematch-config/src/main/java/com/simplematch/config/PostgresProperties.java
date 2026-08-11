package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently bindable PostgreSQL connection capability.
 *
 * @param dsn JDBC URL or supported PostgreSQL DSN
 */
@ConfigurationProperties("simplematch.postgres")
public record PostgresProperties(String dsn) {
  /** Normalizes an absent DSN to the local SimpleMatch PostgreSQL database. */
  public PostgresProperties {
    dsn = dsn == null ? "jdbc:postgresql://localhost:5432/simplematch" : dsn;
  }

  static PostgresProperties defaults() {
    return new PostgresProperties(null);
  }
}
