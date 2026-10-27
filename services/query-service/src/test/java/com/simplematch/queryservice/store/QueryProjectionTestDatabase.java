package com.simplematch.queryservice.store;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Opens an isolated H2 fixture or a PostgreSQL container owned by the test JVM. */
public final class QueryProjectionTestDatabase {
  private static final String OWNER_MARKER = "simplematch-query-postgres-jdbc-test";

  private QueryProjectionTestDatabase() {}

  /** Opens and migrates only an isolated test database after validating its ownership. */
  public static DriverManagerDataSource open() {
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
    final PostgreSQLContainer container = PostgresFixture.CONTAINER;
    final String url = container.getJdbcUrl();
    final String database = container.getDatabaseName();
    if (!database.matches("simplematch_query_jdbc_[0-9a-f]+")
        || !url.startsWith("jdbc:postgresql://")
        || !("/" + database).equals(URI.create(url.substring(5)).getPath())) {
      throw new IllegalStateException("query JDBC tests require a test-owned scratch database");
    }
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(url);
    dataSource.setUsername(container.getUsername());
    dataSource.setPassword(container.getPassword());
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

  /** Migrates the shared fixture and supplies the existing Python collector environment. */
  static Map<String, String> collectorEnvironment() {
    final DriverManagerDataSource dataSource = open();
    return Map.of(
        "QUERY_SERVICE_JDBC_TEST_URL", dataSource.getUrl(),
        "QUERY_SERVICE_JDBC_TEST_DATABASE", PostgresFixture.CONTAINER.getDatabaseName(),
        "QUERY_SERVICE_JDBC_TEST_USER", dataSource.getUsername(),
        "QUERY_SERVICE_JDBC_TEST_PASSWORD", dataSource.getPassword());
  }

  /** Starts one container for all PostgreSQL test classes. Ryuk removes it at JVM exit. */
  private static final class PostgresFixture {
    private static final PostgreSQLContainer CONTAINER = start();

    private PostgresFixture() {}

    private static PostgreSQLContainer start() {
      final PostgreSQLContainer container = new PostgreSQLContainer("postgres:18.4")
          .withDatabaseName("simplematch_query_jdbc_" + UUID.randomUUID().toString().replace("-", ""))
          .withUsername("query_test")
          .withPassword(UUID.randomUUID().toString());
      container.start();
      final DriverManagerDataSource dataSource = new DriverManagerDataSource(
          container.getJdbcUrl(), container.getUsername(), container.getPassword());
      new JdbcTemplate(dataSource).execute(
          "COMMENT ON DATABASE \"" + container.getDatabaseName() + "\" IS '" + OWNER_MARKER + "'");
      return container;
    }
  }
}
