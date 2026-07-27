package com.simplematch.marketdatapublisher.config;

import java.net.URI;
import java.net.URISyntaxException;

/** Parsed PostgreSQL connection data for the service-owned datasource. */
public record PostgresJdbcConfig(String jdbcUrl, String username, String password) {
  /**
   * Converts a JDBC URL or PostgreSQL URI to JDBC connection settings.
   *
   * @param rawDsn JDBC URL or PostgreSQL URI supplied by service configuration
   * @return parsed JDBC settings, including URI credentials when provided
   * @throws IllegalStateException if the DSN is blank, malformed, or not PostgreSQL
   */
  public static PostgresJdbcConfig parse(String rawDsn) {
    if (rawDsn == null || rawDsn.isBlank()) {
      throw new IllegalStateException("postgres.dsn must not be blank");
    }
    if (rawDsn.startsWith("jdbc:")) {
      return new PostgresJdbcConfig(rawDsn, null, null);
    }
    try {
      final URI uri = new URI(rawDsn);
      if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
        throw new IllegalStateException("postgres.dsn must use PostgreSQL");
      }
      if (uri.getHost() == null || uri.getPath() == null || uri.getPath().isBlank()) {
        throw new IllegalStateException("postgres.dsn must identify a host and database");
      }
      final String[] credentials = uri.getUserInfo() == null ? new String[0] : uri.getUserInfo().split(":", 2);
      final int port = uri.getPort() > 0 ? uri.getPort() : 5432;
      return new PostgresJdbcConfig(
          "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath(),
          credentials.length == 0 ? null : credentials[0],
          credentials.length < 2 ? null : credentials[1]);
    } catch (URISyntaxException exception) {
      throw new IllegalStateException("postgres.dsn must be a valid URI", exception);
    }
  }
}
