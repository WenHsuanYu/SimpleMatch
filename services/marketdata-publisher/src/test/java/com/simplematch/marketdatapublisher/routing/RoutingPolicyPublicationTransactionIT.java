package com.simplematch.marketdatapublisher.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(RoutingPolicyPublicationTransactionIT.TestConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RoutingPolicyPublicationTransactionIT {
  private static final UUID SOURCE_SNAPSHOT_ID =
      UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001");
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 27);
  private static final Instant PUBLISHED_AT = Instant.parse("2026-07-27T00:00:00Z");
  private static final InstrumentIdentity AAPL = new InstrumentIdentity("AAPL", "XTAI");
  private static final InstrumentIdentity TSLA = new InstrumentIdentity("TSLA", "ROCO");
  private static final InstrumentIdentity MSFT = new InstrumentIdentity("MSFT", "XTAI");

  private final JdbcTemplate jdbcTemplate;
  private final RoutingPolicyApplicationService publicationService;
  private final TestRoutingPolicyOutbox outbox;

  RoutingPolicyPublicationTransactionIT(
      JdbcTemplate jdbcTemplate,
      RoutingPolicyApplicationService publicationService,
      TestRoutingPolicyOutbox outbox) {
    this.jdbcTemplate = jdbcTemplate;
    this.publicationService = publicationService;
    this.outbox = outbox;
  }

  @BeforeEach
  void clearDatabase() {
    jdbcTemplate.update("DELETE FROM marketdata_publisher.outbox");
    jdbcTemplate.update("DELETE FROM marketdata_publisher.routing_policy_assignments");
    jdbcTemplate.update("DELETE FROM marketdata_publisher.routing_policies");
    jdbcTemplate.update("DELETE FROM marketdata_publisher.market_snapshots");
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.market_snapshots (
          snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
          snapshot_payload, active, active_trading_day, published_at_unix_ms
        ) VALUES (?, ?, 1, 'twse-tpex-2026-07-27', 1, ?, X'01', TRUE, ?, 1)
        """,
        SOURCE_SNAPSHOT_ID,
        TRADING_DAY,
        "a".repeat(64),
        TRADING_DAY);
    outbox.clearFailures();
  }

  @DisplayName("policy, assignments, and binary outbox event commit atomically")
  @Test
  void commitsCompletePolicyAndOutboxTogether() throws Exception {
    final RoutingPolicyPublicationResult result = publicationService.publishRoutingPolicy(policy());

    assertThat(AopUtils.isAopProxy(publicationService)).isTrue();
    assertThat(result.duplicate()).isFalse();
    assertThat(result.routingPolicyId()).isEqualTo(policy().identity().routingPolicyId());
    assertThat(count("marketdata_publisher.routing_policies")).isEqualTo(1);
    assertThat(count("marketdata_publisher.routing_policy_assignments")).isEqualTo(2);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
    final byte[] payload =
        jdbcTemplate.queryForObject("SELECT payload FROM marketdata_publisher.outbox", byte[].class);
    final var event = com.simplematch.contracts.routing.v2.RoutingPolicy.parseFrom(payload);
    assertThat(event.getRoutingPolicyId()).isEqualTo(policy().identity().routingPolicyId().toString());
    assertThat(event.getSourceMarketSnapshotId())
        .isEqualTo(policy().identity().sourceMarketSnapshotId().toString());
    assertThat(event.getAssignmentsList()).hasSize(2);
    assertThat(event.getAssignments(0).getInstrument().getSymbol()).isEqualTo("AAPL");
    assertThat(event.getAssignments(0).getRoutingPartition()).isEqualTo(7);
    assertThat(jdbcTemplate.queryForObject("SELECT topic FROM marketdata_publisher.outbox", String.class))
        .isEqualTo(RoutingPolicyApplicationService.ROUTING_POLICY_PUBLISHED_TOPIC);
  }

  @DisplayName("the same policy is idempotent and does not create a second outbox event")
  @Test
  void returnsExistingPolicyForDuplicatePublication() throws RoutingPolicyPublicationFailure {
    final RoutingPolicyPublicationResult first = publicationService.publishRoutingPolicy(policy());
    final RoutingPolicyPublicationResult duplicate =
        publicationService.publishRoutingPolicy(policy());

    assertThat(duplicate.routingPolicyId()).isEqualTo(first.routingPolicyId());
    assertThat(duplicate.duplicate()).isTrue();
    assertThat(count("marketdata_publisher.routing_policies")).isEqualTo(1);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
  }

  @DisplayName("overlapping intervals are rejected before a second durable policy exists")
  @Test
  void rejectsOverlappingPolicyInterval() throws RoutingPolicyPublicationFailure {
    publicationService.publishRoutingPolicy(policy());
    final RoutingPolicy overlapping =
        policy(
            UUID.fromString("0194a8f1-7c77-7b38-9e2d-2a5fdd0f7c01"),
            Instant.parse("2026-07-27T00:30:00Z"),
            Instant.parse("2026-07-27T01:30:00Z"));

    assertThatThrownBy(() -> publicationService.publishRoutingPolicy(overlapping))
        .isInstanceOf(RoutingPolicyPublicationConflictException.class)
        .hasMessageContaining("overlaps");
    assertThat(count("marketdata_publisher.routing_policies")).isEqualTo(1);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
  }

  @DisplayName("an adjacent policy may add an instrument without changing existing routes")
  @Test
  void publishesAdjacentPolicyWithAdditionalInstrument() throws RoutingPolicyPublicationFailure {
    publicationService.publishRoutingPolicy(policy());
    final RoutingPolicy expanded =
        policy(
            UUID.fromString("0194a8f1-7c77-7b38-9e2d-2a5fdd0f7c03"),
            Instant.parse("2026-07-27T06:00:00Z"),
            Instant.parse("2026-07-27T12:00:00Z"),
            List.of(
                new RoutingAssignment(TSLA, 11),
                new RoutingAssignment(AAPL, 7),
                new RoutingAssignment(MSFT, 5)));

    publicationService.publishRoutingPolicy(expanded);

    assertThat(count("marketdata_publisher.routing_policies")).isEqualTo(2);
    assertThat(count("marketdata_publisher.routing_policy_assignments")).isEqualTo(5);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(2);
  }

  @DisplayName("a later policy cannot move an instrument after its earlier interval ends")
  @Test
  void rejectsIntradayReassignmentAndKeepsPublicationAtomic() throws RoutingPolicyPublicationFailure {
    publicationService.publishRoutingPolicy(policy());
    final RoutingPolicy reassigned =
        policy(
            UUID.fromString("0194a8f1-7c77-7b38-9e2d-2a5fdd0f7c04"),
            Instant.parse("2026-07-27T06:00:00Z"),
            Instant.parse("2026-07-27T12:00:00Z"),
            List.of(new RoutingAssignment(TSLA, 11), new RoutingAssignment(AAPL, 8)));

    assertThatThrownBy(() -> publicationService.publishRoutingPolicy(reassigned))
        .isInstanceOf(RoutingPolicyPublicationConflictException.class)
        .hasMessageContaining("reassigns");
    assertThat(count("marketdata_publisher.routing_policies")).isEqualTo(1);
    assertThat(count("marketdata_publisher.routing_policy_assignments")).isEqualTo(2);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
  }

  @DisplayName("a policy published out of effective order is rejected")
  @Test
  void rejectsOutOfOrderPolicyPublication() throws RoutingPolicyPublicationFailure {
    publicationService.publishRoutingPolicy(
        policy(
            UUID.fromString("0194a8f1-7c77-7b38-9e2d-2a5fdd0f7c05"),
            Instant.parse("2026-07-27T06:00:00Z"),
            Instant.parse("2026-07-27T12:00:00Z")));
    final RoutingPolicy earlier =
        policy(
            UUID.fromString("0194a8f1-7c77-7b38-9e2d-2a5fdd0f7c06"),
            Instant.parse("2026-07-27T00:30:00Z"),
            Instant.parse("2026-07-27T05:30:00Z"));

    assertThatThrownBy(() -> publicationService.publishRoutingPolicy(earlier))
        .isInstanceOf(RoutingPolicyPublicationConflictException.class)
        .hasMessageContaining("effective order");
    assertThat(count("marketdata_publisher.routing_policies")).isEqualTo(1);
    assertThat(count("marketdata_publisher.outbox")).isEqualTo(1);
  }

  @DisplayName("an unknown source snapshot cannot become a routing policy")
  @Test
  void rejectsUnknownSourceSnapshot() {
    final RoutingPolicy unknownSource =
        new RoutingPolicy(
            new RoutingPolicyIdentity(
                UUID.fromString("0194a8f1-7c77-7b38-9e2d-2a5fdd0f7c02"),
                UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1002"),
                TRADING_DAY),
            policy().effectiveInterval(),
            16,
            policy().assignments());

    assertThatThrownBy(() -> publicationService.publishRoutingPolicy(unknownSource))
        .isInstanceOf(RoutingPolicyPublicationConflictException.class)
        .hasMessageContaining("source market snapshot");
    assertThat(count("marketdata_publisher.routing_policies")).isZero();
    assertThat(count("marketdata_publisher.outbox")).isZero();
  }

  @DisplayName("an outbox failure rolls back the complete policy and assignments")
  @Test
  void rollsBackWhenOutboxInsertionFails() {
    outbox.failNextInsertWithUncheckedFailure();

    assertThatThrownBy(() -> publicationService.publishRoutingPolicy(policy()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated outbox failure");
    assertThat(count("marketdata_publisher.routing_policies")).isZero();
    assertThat(count("marketdata_publisher.routing_policy_assignments")).isZero();
    assertThat(count("marketdata_publisher.outbox")).isZero();
  }

  private RoutingPolicy policy() {
    return policy(
        UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01"),
        Instant.parse("2026-07-27T00:00:00Z"),
        Instant.parse("2026-07-27T06:00:00Z"));
  }

  private RoutingPolicy policy(UUID policyId, Instant from, Instant until) {
    return policy(
        policyId,
        from,
        until,
        List.of(new RoutingAssignment(TSLA, 11), new RoutingAssignment(AAPL, 7)));
  }

  private RoutingPolicy policy(
      UUID policyId, Instant from, Instant until, List<RoutingAssignment> assignments) {
    return new RoutingPolicy(
        new RoutingPolicyIdentity(policyId, SOURCE_SNAPSHOT_ID, TRADING_DAY),
        new RoutingPolicyInterval(from, until),
        16,
        assignments);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  @Configuration
  @EnableTransactionManagement
  static class TestConfiguration {
    @Bean
    DriverManagerDataSource dataSource() {
      final DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.h2.Driver");
      dataSource.setUrl(
          "jdbc:h2:mem:routingpolicy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS marketdata_publisher\\;SET SCHEMA marketdata_publisher");
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
    RoutingPolicyRepository routingPolicyRepository(JdbcTemplate jdbcTemplate) {
      return new JdbcRoutingPolicyRepository(jdbcTemplate);
    }

    @Bean
    TestRoutingPolicyOutbox routingPolicyOutbox(JdbcTemplate jdbcTemplate) {
      return new TestRoutingPolicyOutbox(new JdbcRoutingPolicyOutbox(jdbcTemplate));
    }

    @Bean
    RoutingPolicyApplicationService routingPolicyApplicationService(
        RoutingPolicyRepository policies, TestRoutingPolicyOutbox outbox) {
      return new RoutingPolicyApplicationService(
          policies,
          outbox,
          new ObjectMapper(),
          Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC),
          UUID::randomUUID);
    }
  }

  static final class TestRoutingPolicyOutbox implements RoutingPolicyOutbox {
    private final RoutingPolicyOutbox delegate;
    private boolean failNextInsert;

    TestRoutingPolicyOutbox(RoutingPolicyOutbox delegate) {
      this.delegate = delegate;
    }

    @Override
    public void insert(RoutingPolicyOutboxRecord record) throws RoutingPolicyPublicationFailure {
      if (failNextInsert) {
        failNextInsert = false;
        throw new IllegalStateException("simulated outbox failure");
      }
      delegate.insert(record);
    }

    void failNextInsertWithUncheckedFailure() {
      failNextInsert = true;
    }

    void clearFailures() {
      failNextInsert = false;
    }
  }
}
