package com.simplematch.marketdatapublisher.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketdatapublisher.snapshot.MarketSnapshotImportService;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(MarketSnapshotPublicationTransactionIT.TestConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MarketSnapshotPublicationTransactionIT {
  private static final String FIXTURE = "fixtures/xtai-and-roco-snapshot.json";

  private final MarketSnapshotImportService importService =
      new MarketSnapshotImportService(new ObjectMapper());

  private final JdbcTemplate jdbcTemplate;
  private final MarketSnapshotApplicationService publicationService;
  private final TestSnapshotRepository snapshots;
  private final TestSnapshotOutbox outbox;

  MarketSnapshotPublicationTransactionIT(
      JdbcTemplate jdbcTemplate,
      MarketSnapshotApplicationService publicationService,
      TestSnapshotRepository snapshots,
      TestSnapshotOutbox outbox) {
    this.jdbcTemplate = jdbcTemplate;
    this.publicationService = publicationService;
    this.snapshots = snapshots;
    this.outbox = outbox;
  }

  @BeforeEach
  void clearDatabase() {
    jdbcTemplate.update("DELETE FROM marketdata_publisher.outbox");
    jdbcTemplate.update("DELETE FROM marketdata_publisher.market_snapshots");
    snapshots.clearFailures();
    outbox.clearFailures();
  }

  @DisplayName(
      "snapshot, activation metadata, and outbox event commit as one public publication outcome")
  @Test
  void commitsSnapshotAndOutboxAtomically() throws IOException, SnapshotPublicationFailure {
    final SnapshotPublicationResult publication = publicationService.publishSnapshot(fixture());

    assertThat(AopUtils.isAopProxy(publicationService)).isTrue();
    assertThat(publication.duplicate()).isFalse();
    assertThat(publication.version()).isEqualTo(1);
    assertThat(count("marketdata_publisher.market_snapshots")).isEqualTo(1);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT active FROM marketdata_publisher.market_snapshots", Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT message_key FROM marketdata_publisher.outbox", String.class))
        .isEqualTo("2026-07-27");
  }

  @DisplayName("JDBC round-trip rehydrates the semantic publication groups")
  @Test
  void roundTripsSemanticPublicationGroups() throws IOException, SnapshotPublicationFailure {
    final PreparedMarketSnapshot prepared = fixture();
    final SnapshotPublicationResult publication = publicationService.publishSnapshot(prepared);

    final PublishedMarketSnapshot stored =
        snapshots
            .findBySourceIdentityAndChecksum(prepared.sourceIdentity(), prepared.checksum())
            .orElseThrow();

    assertThat(stored.identity())
        .isEqualTo(
            new SnapshotIdentity(
                publication.snapshotId(), prepared.tradingDay(), publication.version()));
    assertThat(stored.provenance())
        .isEqualTo(
            new SnapshotProvenance(
                prepared.sourceIdentity(),
                prepared.sourceTimestampUnixMs(),
                prepared.checksum()));
    assertThat(stored.canonicalContent()).isEqualTo(prepared.canonicalContent());
    assertThat(stored.publication())
        .isEqualTo(
            new SnapshotPublicationState(
                true, Instant.parse("2026-07-27T00:00:00Z")));
  }

  @DisplayName(
      "an identical source checksum returns the original durable publication without a second outbox event")
  @Test
  void returnsExistingPublicationForDuplicateImport()
      throws IOException, SnapshotPublicationFailure {
    final SnapshotPublicationResult first = publicationService.publishSnapshot(fixture());
    final SnapshotPublicationResult duplicate = publicationService.publishSnapshot(fixture());

    assertThat(duplicate.snapshotId()).isEqualTo(first.snapshotId());
    assertThat(duplicate.version()).isEqualTo(first.version());
    assertThat(duplicate.duplicate()).isTrue();
    assertThat(count("marketdata_publisher.market_snapshots")).isEqualTo(1);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
  }

  @DisplayName("a first snapshot write failure leaves neither snapshot metadata nor outbox state")
  @Test
  void rollsBackWhenSnapshotPersistenceFails() throws IOException {
    snapshots.failNextInsertWithCheckedFailure();

    assertThatThrownBy(() -> publicationService.publishSnapshot(fixture()))
        .isInstanceOf(SnapshotPublicationFailure.class);

    assertNoPartialState();
  }

  @DisplayName(
      "a later outbox write failure rolls back the already inserted snapshot and activation")
  @Test
  void rollsBackWhenOutboxInsertionFails() throws IOException {
    outbox.failNextInsertWithUncheckedFailure();

    assertThatThrownBy(() -> publicationService.publishSnapshot(fixture()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated outbox failure");

    assertNoPartialState();
  }

  @DisplayName(
      "a competing version constraint becomes a stable publication conflict without partial state")
  @Test
  void mapsConcurrentPublicationConstraintToStableConflict() throws IOException {
    snapshots.failNextInsertWithConflict();

    assertThatThrownBy(() -> publicationService.publishSnapshot(fixture()))
        .isInstanceOf(SnapshotPublicationConflictException.class)
        .hasMessage("a conflicting market snapshot publication already exists");

    assertNoPartialState();
  }

  @DisplayName(
      "a database column constraint rejects invalid durable metadata without an outbox side effect")
  @Test
  void rollsBackWhenDatabaseConstraintRejectsSnapshotMetadata() throws IOException {
    final String tooLongSourceIdentity = "source-" + "x".repeat(200);
    final PreparedMarketSnapshot invalidForDatabase =
        importService.prepare(
            new String(fixtureBytes(), StandardCharsets.UTF_8)
                .replace("twse-tpex-2026-07-27", tooLongSourceIdentity)
                .getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> publicationService.publishSnapshot(invalidForDatabase))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertNoPartialState();
  }

  @DisplayName(
      "two first publishers race on a real database uniqueness constraint and leave one complete result")
  @Test
  void resolvesActualConcurrentFirstPublicationsAsOneResultAndOneConflict() throws Exception {
    final PreparedMarketSnapshot first = fixture();
    final PreparedMarketSnapshot changed = correctedFixture();
    snapshots.synchronizeNextVersionForTwoPublishers();
    final ExecutorService publishers = Executors.newFixedThreadPool(2);

    try {
      final List<Future<Object>> outcomes =
          List.of(
              publishers.submit(() -> publishOutcome(first)),
              publishers.submit(() -> publishOutcome(changed)));
      final List<Object> completed = List.of(outcomes.get(0).get(), outcomes.get(1).get());

      assertThat(completed).anyMatch(SnapshotPublicationResult.class::isInstance);
      assertThat(completed).anyMatch(SnapshotPublicationConflictException.class::isInstance);
      assertThat(count("marketdata_publisher.market_snapshots")).isEqualTo(1);
      assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
    } finally {
      publishers.shutdownNow();
    }
  }

  @DisplayName(
      "changed content for a trading day allocates a new version and replaces the single active snapshot")
  @Test
  void publishesChangedContentAsNewVersion() throws IOException, SnapshotPublicationFailure {
    final SnapshotPublicationResult first = publicationService.publishSnapshot(fixture());
    final SnapshotPublicationResult second = publicationService.publishSnapshot(correctedFixture());

    assertThat(second.version()).isEqualTo(first.version() + 1);
    assertThat(count("marketdata_publisher.market_snapshots")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketdata_publisher.market_snapshots WHERE active",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(2);
  }

  private PreparedMarketSnapshot fixture() throws IOException {
    return importService.prepare(fixtureBytes());
  }

  private PreparedMarketSnapshot correctedFixture() throws IOException {
    final String changedSource =
        new String(fixtureBytes(), StandardCharsets.UTF_8)
            .replace("twse-tpex-2026-07-27", "twse-tpex-correction-2026-07-27")
            .replace("\"referencePrice\": \"1000\"", "\"referencePrice\": \"1000.5\"")
            .replace("\"lowerPriceLimit\": \"900\"", "\"lowerPriceLimit\": \"900.5\"")
            .replace("\"upperPriceLimit\": \"1100\"", "\"upperPriceLimit\": \"1100.5\"");
    return importService.prepare(changedSource.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] fixtureBytes() throws IOException {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
      return input.readAllBytes();
    }
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private void assertNoPartialState() {
    assertThat(count("marketdata_publisher.market_snapshots")).isZero();
    assertThat(count("marketdata_publisher.outbox")).isZero();
  }

  private Object publishOutcome(PreparedMarketSnapshot snapshot) {
    try {
      return publicationService.publishSnapshot(snapshot);
    } catch (SnapshotPublicationFailure | RuntimeException exception) {
      return exception;
    }
  }

  @Configuration
  @EnableTransactionManagement
  static class TestConfiguration {
    @Bean
    DriverManagerDataSource dataSource() {
      final DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.h2.Driver");
      dataSource.setUrl(
          "jdbc:h2:mem:marketdata-publisher;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS marketdata_publisher\\;SET SCHEMA marketdata_publisher");
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/marketdata-publisher")
          .load()
          .migrate();
      return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DriverManagerDataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TestSnapshotRepository snapshotRepository(JdbcTemplate jdbcTemplate) {
      return new TestSnapshotRepository(new JdbcMarketSnapshotRepository(jdbcTemplate));
    }

    @Bean
    TestSnapshotOutbox snapshotOutbox(JdbcTemplate jdbcTemplate) {
      return new TestSnapshotOutbox(new JdbcSnapshotOutbox(jdbcTemplate));
    }

    @Bean
    MarketSnapshotApplicationService marketSnapshotApplicationService(
        TestSnapshotRepository snapshots, TestSnapshotOutbox outbox) {
      return new MarketSnapshotApplicationService(
          snapshots,
          outbox,
          new ObjectMapper(),
          Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
          UUID::randomUUID,
          UUID::randomUUID);
    }
  }
}
