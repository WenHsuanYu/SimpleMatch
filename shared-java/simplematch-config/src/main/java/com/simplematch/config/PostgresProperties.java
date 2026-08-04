package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independently bindable PostgreSQL connection capability. */
@ConfigurationProperties("simplematch.postgres")
public record PostgresProperties(String dsn) {
  /** Normalizes an absent DSN to the local SimpleMatch PostgreSQL database. */
  public PostgresProperties {
    dsn = PlatformPropertyDefaults.string(dsn, "jdbc:postgresql://localhost:5432/simplematch");
  }

  static PostgresProperties defaults() {
    return new PostgresProperties(null);
  }
}
