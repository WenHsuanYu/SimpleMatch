package com.simplematch.config.testsupport;

import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Supplies isolated PostgreSQL databases to tests that exercise actual persistence behavior. */
public final class PostgresTestDatabase {
  private PostgresTestDatabase() {}

  /**
   * Creates an empty database in the test-owned container without initializing service tables.
   *
   * <p>The caller runs its canonical Flyway migrations. One container serves the test JVM, and
   * Testcontainers removes its resources when the JVM exits. Each call has a separate database.
   *
   * @param service service identity whose schema is selected on connections
   * @return a datasource for the new isolated database
   * @throws IllegalStateException if database creation fails
   */
  public static DriverManagerDataSource create(String service) {
    if (!service.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("A canonical service identity is required");
    }
    final var container = Fixture.CONTAINER;
    final String database = "simplematch_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = container.createConnection("");
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE \"" + database + "\"");
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot create an isolated PostgreSQL test database", failure);
    }
    final var source = new DriverManagerDataSource(
        container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + database),
        container.getUsername(), container.getPassword());
    source.setSchema(service.replace('-', '_'));
    return source;
  }

  /**
   * Supplies the canonical PostgreSQL DSN for a Spring test using a fixture-created datasource.
   *
   * @param source datasource returned by {@link #create(String)}
   * @return PostgreSQL URI with the fixture's generated connection credentials
   */
  public static String dsn(DriverManagerDataSource source) {
    return java.util.Objects.requireNonNull(source.getUrl()).replace("jdbc:postgresql://",
        "postgresql://" + source.getUsername() + ":" + source.getPassword() + "@");
  }

  private static final class Fixture {
    private static final PostgreSQLContainer CONTAINER = start();

    private Fixture() {}

    private static PostgreSQLContainer start() {
      final var container = new PostgreSQLContainer("postgres:18.4")
          .withDatabaseName("simplematch_tests")
          .withUsername("test_owner")
          .withPassword(UUID.randomUUID().toString())
          .withLabel("simplematch.test", "postgres-fixture");
      container.start();
      return container;
    }
  }
}
