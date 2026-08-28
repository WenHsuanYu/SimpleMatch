package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import com.simplematch.riskservice.testsupport.H2TestDatabaseUrl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the complete barrier set participates in one real database transaction. */
class TradingSessionBarrierServiceTransactionTest {
  private JdbcTemplate jdbcTemplate;
  private MatchingBarrierOutboxFactory factory;
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(H2TestDatabaseUrl.uniqueRiskServiceUrl());
    migrate(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    factory =
        new MatchingBarrierOutboxFactory(
            "matching.commands",
            artifact(),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC));
  }

  @Test
  void rollsBackTheWholeBarrierSetWhenAnyInsertFails() {
    final OutboxRepository jdbcOutbox = new JdbcOutboxRepository(jdbcTemplate);
    final AtomicInteger attempts = new AtomicInteger();
    final OutboxRepository failingOutbox =
        new OutboxRepository() {
          @Override
          public void insert(OutboxRecord record) {
            jdbcOutbox.insert(record);
          }

          @Override
          public boolean insertIfAbsent(OutboxRecord record) {
            if (attempts.incrementAndGet() == 8) {
              throw new IllegalStateException("injected barrier failure");
            }
            return jdbcOutbox.insertIfAbsent(record);
          }
        };
    final TradingSessionBarrierService failingService =
        new TradingSessionBarrierService(factory, failingOutbox, transactionTemplate);

    assertThatThrownBy(() -> failingService.close("2026-08-11-regular"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected barrier failure");
    assertThat(outboxRowCount()).isZero();

    final TradingSessionBarrierService service =
        new TradingSessionBarrierService(factory, jdbcOutbox, transactionTemplate);
    assertThat(service.close("2026-08-11-regular")).isEqualTo(15);
    assertThat(service.close("2026-08-11-regular")).isZero();
    assertThat(outboxRowCount()).isEqualTo(15);
  }

  private int outboxRowCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class);
  }

  private static void migrate(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/risk-service")
        .load()
        .migrate();
  }

  private static VerifiedMarketReferenceArtifact artifact() {
    try {
      return new MarketReferenceArtifactStartupValidator(new ObjectMapper())
          .validate(
              resource("/market-reference/market_reference.json"),
              new String(
                      resource("/market-reference/market_reference.sha256"),
                      StandardCharsets.US_ASCII)
                  .trim(),
              LocalDate.of(2026, 8, 11));
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static byte[] resource(String path) throws IOException {
    try (var stream =
        TradingSessionBarrierServiceTransactionTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IOException("missing test resource: " + path);
      }
      return stream.readAllBytes();
    }
  }
}
