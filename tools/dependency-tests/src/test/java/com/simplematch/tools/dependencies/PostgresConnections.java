package com.simplematch.tools.dependencies;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Supplies read-only operator connections without forwarding credentials to command arguments. */
final class PostgresConnections {
  private PostgresConnections() {}

  static DriverManagerDataSource live() {
    if (!value("SIMPLEMATCH_LIVE_POSTGRES_SSLMODE", "verify-full").equals("verify-full")) {
      throw new IllegalArgumentException("PostgreSQL live checks require SSLMODE=verify-full");
    }
    final DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(
        url(
            required("SIMPLEMATCH_LIVE_POSTGRES_HOST"),
            value("SIMPLEMATCH_LIVE_POSTGRES_PORT", "5432"),
            required("SIMPLEMATCH_LIVE_POSTGRES_DATABASE")));
    final String custom = System.getenv("SIMPLEMATCH_LIVE_POSTGRES_JDBC_URL");
    if (custom != null && !custom.isBlank()) {
      source.setUrl(custom);
    }
    source.setUsername(required("SIMPLEMATCH_LIVE_POSTGRES_USER"));
    source.setPassword(required("SIMPLEMATCH_LIVE_POSTGRES_PASSWORD"));
    final Properties properties = new Properties();
    properties.setProperty("sslmode", "verify-full");
    final String certificate = System.getenv("SIMPLEMATCH_LIVE_POSTGRES_SSLROOTCERT");
    if (certificate != null && !certificate.isBlank()) {
      properties.setProperty("sslrootcert", certificate);
    }
    readOnly(source, properties);
    return source;
  }

  static DriverManagerDataSource plans() {
    final DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(
        url(
            value("CI_POSTGRES_HOST", "127.0.0.1"),
            value("CI_POSTGRES_PORT", "5432"),
            required("SIMPLEMATCH_PLAN_DATABASE")));
    final Properties properties = new Properties();
    source.setUsername(value("CI_POSTGRES_USER", "simplematch"));
    source.setPassword(value("CI_POSTGRES_PASSWORD", "simplematch"));
    final String mode = System.getenv("PGSSLMODE");
    if (mode != null && !mode.isBlank()) {
      properties.setProperty("sslmode", mode);
    }
    final String certificate = System.getenv("PGSSLROOTCERT");
    if (certificate != null && !certificate.isBlank()) {
      properties.setProperty("sslrootcert", certificate);
    }
    readOnly(source, properties);
    return source;
  }

  private static void readOnly(DriverManagerDataSource source, Properties properties) {
    properties.setProperty("readOnly", "true");
    properties.setProperty("connectTimeout", "10");
    properties.setProperty("socketTimeout", "30");
    properties.setProperty("options", "-c default_transaction_read_only=on");
    source.setConnectionProperties(properties);
    if ("verify-full".equals(properties.getProperty("sslmode"))) {
      try {
        for (var property :
            DriverManager.getDriver(source.getUrl()).getPropertyInfo(source.getUrl(), properties)) {
          if (property.name.equals("sslmode") && !"verify-full".equals(property.value)) {
            throw new IllegalArgumentException(
                "PostgreSQL JDBC URL must retain sslmode=verify-full");
          }
        }
      } catch (SQLException failure) {
        throw new IllegalArgumentException("Invalid PostgreSQL JDBC settings", failure);
      }
    }
  }

  private static String url(String host, String port, String database) {
    final String address = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    return "jdbc:postgresql://" + address + ":" + Integer.parseInt(port) + "/" + database;
  }

  static String required(String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static String value(String name, String fallback) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
