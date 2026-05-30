package com.simplematch.accountservice.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

record PostgresJdbcConfig(String jdbcUrl, String username, String password) {
  static PostgresJdbcConfig parse(String rawDsn) {
    if (rawDsn == null || rawDsn.isBlank()) {
      throw new IllegalStateException("postgres.dsn must not be blank");
    }
    if (rawDsn.startsWith("jdbc:")) {
      return new PostgresJdbcConfig(rawDsn, null, null);
    }

    try {
      final URI uri = new URI(rawDsn);
      final String scheme = Objects.requireNonNullElse(uri.getScheme(), "");
      if (!"postgresql".equals(scheme) && !"postgres".equals(scheme)) {
        throw new IllegalStateException("unsupported postgres.dsn scheme: " + rawDsn);
      }

      final String userInfo = uri.getUserInfo();
      String username = null;
      String password = null;
      if (userInfo != null && !userInfo.isBlank()) {
        final String[] parts = userInfo.split(":", 2);
        username = parts[0];
        if (parts.length > 1) {
          password = parts[1];
        }
      }

      final int port = uri.getPort() > 0 ? uri.getPort() : 5432;
      final String jdbcUrl = "jdbc:postgresql://"
          + uri.getHost()
          + ":"
          + port
          + uri.getPath();
      return new PostgresJdbcConfig(jdbcUrl, username, password);
    } catch (URISyntaxException syntaxException) {
      throw new IllegalStateException("failed to parse postgres.dsn: " + rawDsn, syntaxException);
    }
  }
}