package com.simplematch.queryservice.store;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Owns PostgreSQL test databases and their canonical Query migrations. */
public final class QueryProjectionTestDatabase {
  private static final String OWNER_MARKER = "simplematch-query-postgres-jdbc-test";

  private QueryProjectionTestDatabase() {}

  /** Opens and migrates only an isolated test database after validating its ownership. */
  public static DriverManagerDataSource open() {
    final DriverManagerDataSource dataSource = postgres(PostgresFixture.CONTAINER.getDatabaseName());
    migrate(dataSource);
    return dataSource;
  }

  private static void migrate(DriverManagerDataSource source) {
    Flyway.configure()
        .dataSource(source)
        .schemas("query_service")
        .defaultSchema("query_service")
        .locations("classpath:db/migration/query-service")
        .load()
        .migrate();
  }

  /** Creates and migrates a separate owned database for schema and reset tests. */
  public static DriverManagerDataSource openIsolated() {
    final String database = "simplematch_query_jdbc_" + UUID.randomUUID().toString().replace("-", "");
    final var admin = credentials(PostgresFixture.CONTAINER.getDatabaseName());
    final var jdbc = new JdbcTemplate(admin);
    jdbc.execute("CREATE DATABASE \"" + database + "\"");
    jdbc.execute("COMMENT ON DATABASE \"" + database + "\" IS '" + OWNER_MARKER + "'");
    final var source = postgres(database);
    migrate(source);
    return source;
  }

  /** Supplies the canonical DSN for Spring's real managed DataSource integration test. */
  public static String canonicalDsn() {
    open();
    final var container = PostgresFixture.CONTAINER;
    return "postgresql://" + container.getUsername() + ":" + container.getPassword()
        + "@" + container.getHost() + ":" + container.getMappedPort(5432)
        + "/" + container.getDatabaseName();
  }

  private static DriverManagerDataSource credentials(String database) {
    final var container = PostgresFixture.CONTAINER;
    final var source = new DriverManagerDataSource();
    source.setDriverClassName("org.postgresql.Driver");
    source.setUrl(container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + database));
    source.setUsername(container.getUsername());
    source.setPassword(container.getPassword());
    return source;
  }

  private static DriverManagerDataSource postgres(String database) {
    final DriverManagerDataSource dataSource = credentials(database);
    final String url = dataSource.getUrl();
    if (url == null || !database.matches("simplematch_query_jdbc_[0-9a-f]+")
        || !url.startsWith("jdbc:postgresql://")
        || !("/" + database).equals(URI.create(url.substring(5)).getPath())) {
      throw new IllegalStateException("query JDBC tests require a test-owned scratch database");
    }
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
