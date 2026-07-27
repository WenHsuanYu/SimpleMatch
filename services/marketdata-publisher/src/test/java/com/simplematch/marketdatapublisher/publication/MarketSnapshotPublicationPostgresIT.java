package com.simplematch.marketdatapublisher.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketdatapublisher.snapshot.MarketSnapshotImportService;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class MarketSnapshotPublicationPostgresIT {
  private static final String DSN_PROPERTY = "phase5.postgres.dsn";
  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    final String dsn = System.getProperty(DSN_PROPERTY, "");
    Assumptions.assumeTrue(!dsn.isBlank(), "set -D" + DSN_PROPERTY + " to run PostgreSQL verification");
    dataSource = new DriverManagerDataSource(dsn);
    jdbcTemplate = new JdbcTemplate(dataSource);
    assertThat(schemaExists()).as("marketdata_publisher must not pre-exist for an isolated verification run").isFalse();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/marketdata-publisher")
        .load()
        .migrate();
  }

  @AfterEach
  void tearDown() {
    if (jdbcTemplate != null && schemaExists()) {
      jdbcTemplate.execute("DROP SCHEMA marketdata_publisher CASCADE");
    }
  }

  @DisplayName("PostgreSQL commits active snapshot metadata and binary outbox rows together")
  @Test
  void commitsPublicationAgainstPostgreSql() throws Exception {
    final TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    final MarketSnapshotApplicationService service = new MarketSnapshotApplicationService(
        new JdbcMarketSnapshotRepository(jdbcTemplate),
        new JdbcSnapshotOutbox(jdbcTemplate),
        new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
        UUID::randomUUID,
        UUID::randomUUID);
    final var snapshot = fixture();

    transaction.executeWithoutResult(ignored -> {
      try {
        service.publishSnapshot(snapshot);
      } catch (SnapshotPublicationFailure exception) {
        throw new IllegalStateException(exception);
      }
    });

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM marketdata_publisher.market_snapshots WHERE active", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM marketdata_publisher.outbox", Integer.class)).isEqualTo(1);
  }

  private boolean schemaExists() {
    return jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'marketdata_publisher')",
        Boolean.class);
  }

  private com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot fixture() throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream("fixtures/xtai-and-roco-snapshot.json")) {
      return new MarketSnapshotImportService(new ObjectMapper()).prepare(input.readAllBytes());
    }
  }
}
