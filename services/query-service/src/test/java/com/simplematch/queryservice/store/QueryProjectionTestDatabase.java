package com.simplematch.queryservice.store;

import java.net.URI;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opens the isolated H2 fixture or an explicitly runner-owned PostgreSQL database. */
final class QueryProjectionTestDatabase {
  private static final String OWNER_MARKER = "simplematch-query-postgres-jdbc-test";

  private QueryProjectionTestDatabase() {}

  static DriverManagerDataSource open() {
    final String backend = System.getProperty("simplematch.query.jdbc.backend", "h2");
    final DriverManagerDataSource dataSource = switch (backend) {
      case "h2" -> h2();
      case "postgresql" -> postgres();
      default -> throw new IllegalArgumentException("unsupported query JDBC test backend");
    };
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("query_service")
        .defaultSchema("query_service")
        .locations("classpath:db/migration/query-service")
        .load()
        .migrate();
    return dataSource;
  }

  private static DriverManagerDataSource h2() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:querytest"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS query_service\\;"
            + "SET SCHEMA query_service");
    return dataSource;
  }

  private static DriverManagerDataSource postgres() {
    final String url = requiredEnvironment("QUERY_SERVICE_JDBC_TEST_URL");
    final String database = requiredEnvironment("QUERY_SERVICE_JDBC_TEST_DATABASE");
    if (!database.matches("simplematch_query_jdbc_[0-9a-f]+")
        || !url.startsWith("jdbc:postgresql://")
        || !("/" + database).equals(URI.create(url.substring(5)).getPath())) {
      throw new IllegalStateException("query JDBC tests require a runner-owned scratch database");
    }
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(url);
    dataSource.setUsername(requiredEnvironment("QUERY_SERVICE_JDBC_TEST_USER"));
    dataSource.setPassword(requiredEnvironment("QUERY_SERVICE_JDBC_TEST_PASSWORD"));
    final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    final Boolean owned = jdbc.queryForObject(
        "SELECT current_database() = ? AND shobj_description(oid, 'pg_database') = ? "
            + "FROM pg_database WHERE datname = current_database()",
        Boolean.class, database, OWNER_MARKER);
    if (!Boolean.TRUE.equals(owned)) {
      throw new IllegalStateException("query JDBC scratch database ownership marker is missing");
    }
    return dataSource;
  }

  private static String requiredEnvironment(String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for PostgreSQL JDBC tests");
    }
    return value;
  }
}
