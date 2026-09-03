package com.simplematch.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Parsed PostgreSQL connection settings derived from one configured JDBC URL or PostgreSQL URI.
 *
 * <p>This centralizes URI normalization so service datasource modules do not each own a subtly
 * different connection-string parser.
 *
 * @param jdbcUrl normalized JDBC URL
 * @param username optional URI user name
 * @param password optional URI password
 */
public record PostgresJdbcUrl(String jdbcUrl, String username, String password) {
  /**
   * Converts a JDBC URL or PostgreSQL URI to JDBC connection settings.
   *
   * @param rawDsn configured JDBC URL or PostgreSQL URI
   * @return normalized JDBC settings
   * @throws IllegalStateException if the DSN is blank, malformed, or not PostgreSQL
   */
  public static PostgresJdbcUrl parse(String rawDsn) {
    requireDsn(rawDsn);
    if (rawDsn.startsWith("jdbc:")) {
      if (!isSupportedJdbcUrl(rawDsn)) {
        throw new IllegalStateException("unsupported postgres.dsn JDBC scheme");
      }
      return new PostgresJdbcUrl(rawDsn, null, null);
    }
    try {
      return parseUri(new URI(rawDsn));
    } catch (URISyntaxException exception) {
      throw new IllegalStateException(
          "failed to parse postgres.dsn as a PostgreSQL URI", exception);
    }
  }

  /**
   * Parses a DSN for a staging or production deployment and requires verified PostgreSQL TLS.
   *
   * <p>The secure environment contract deliberately rejects H2 and plain or merely encrypted
   * PostgreSQL connections. Hostname verification and a configured CA are required so a deployed
   * service cannot silently downgrade its database transport.
   *
   * @param rawDsn configured JDBC URL or PostgreSQL URI
   * @return normalized PostgreSQL JDBC settings
   * @throws IllegalStateException if the DSN is not PostgreSQL or lacks verified TLS settings
   */
  public static PostgresJdbcUrl parseSecure(String rawDsn) {
    final PostgresJdbcUrl parsed = parse(rawDsn);
    if (!parsed.jdbcUrl().startsWith("jdbc:postgresql:")) {
      throw new IllegalStateException(
          "secure postgres.dsn requires a PostgreSQL JDBC URL with verified TLS");
    }
    final String sslMode = queryParameter(parsed.jdbcUrl(), "sslmode");
    final String sslRootCertificate = queryParameter(parsed.jdbcUrl(), "sslrootcert");
    if (!"verify-full".equalsIgnoreCase(sslMode)
        || sslRootCertificate == null
        || sslRootCertificate.isBlank()) {
      throw new IllegalStateException(
          "secure postgres.dsn requires sslmode=verify-full and sslrootcert");
    }
    return parsed;
  }

  private static void requireDsn(String rawDsn) {
    if (rawDsn == null || rawDsn.isBlank()) {
      throw new IllegalStateException("postgres.dsn must not be blank");
    }
  }

  private static PostgresJdbcUrl parseUri(URI uri) {
    if (!isPostgresScheme(uri)) {
      throw new IllegalStateException(
          "unsupported postgres.dsn scheme: " + String.valueOf(uri.getScheme()));
    }
    if (uri.getHost() == null || uri.getPath() == null || uri.getPath().isBlank()) {
      throw new IllegalStateException("postgres.dsn must identify a host and database");
    }
    final Credentials credentials = credentials(uri);
    return new PostgresJdbcUrl(jdbcUrl(uri), credentials.username(), credentials.password());
  }

  private static boolean isPostgresScheme(URI uri) {
    return "postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme());
  }

  private static boolean isSupportedJdbcUrl(String rawDsn) {
    return rawDsn.startsWith("jdbc:postgresql:") || rawDsn.startsWith("jdbc:h2:");
  }

  private static String queryParameter(String jdbcUrl, String parameterName) {
    final int queryStart = jdbcUrl.indexOf('?');
    if (queryStart < 0 || queryStart == jdbcUrl.length() - 1) {
      return null;
    }
    String value = null;
    for (String parameter : jdbcUrl.substring(queryStart + 1).split("&", -1)) {
      final int separator = parameter.indexOf('=');
      final String name = separator < 0 ? parameter : parameter.substring(0, separator);
      if (!parameterName.equalsIgnoreCase(name)) {
        continue;
      }
      if (value != null) {
        throw new IllegalStateException(
            "secure postgres.dsn must not repeat the " + parameterName + " parameter");
      }
      value = separator < 0 ? "" : parameter.substring(separator + 1);
    }
    return value;
  }

  private static Credentials credentials(URI uri) {
    final String userInfo = uri.getUserInfo();
    if (userInfo == null || userInfo.isBlank()) {
      return new Credentials(null, null);
    }
    final String[] parts = userInfo.split(":", 2);
    if (parts.length == 1) {
      return new Credentials(parts[0], null);
    }
    return new Credentials(parts[0], parts[1]);
  }

  private static String jdbcUrl(URI uri) {
    final int port = uri.getPort() > 0 ? uri.getPort() : 5432;
    final String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
    return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query;
  }

  private record Credentials(String username, String password) {}
}
