package com.simplematch.riskservice.routing;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.riskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.routing.v2.InstrumentRoutingAssignment;
import com.simplematch.contracts.routing.v2.RoutingPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(RoutingPolicyProjectionTransactionIT.TestConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RoutingPolicyProjectionTransactionIT {
  private static final UUID POLICY_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01");
  private static final UUID SNAPSHOT_ID =
      UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001");
  private static final Instant NOW = Instant.parse("2026-07-27T01:00:00Z");

  private final JdbcTemplate jdbcTemplate;
  private final RoutingPolicyProjectionService projectionService;
  private final TestRoutingPolicyProjectionRepository repository;

  RoutingPolicyProjectionTransactionIT(
      JdbcTemplate jdbcTemplate,
      RoutingPolicyProjectionService projectionService,
      TestRoutingPolicyProjectionRepository repository) {
    this.jdbcTemplate = jdbcTemplate;
    this.projectionService = projectionService;
    this.repository = repository;
  }

  @BeforeEach
  void clearDatabase() {
    jdbcTemplate.update("DELETE FROM risk_service.routing_policy_assignments");
    jdbcTemplate.update("DELETE FROM risk_service.routing_policies");
    repository.clearFailures();
  }

  @DisplayName("complete policy projection commits parent, assignments, and active state together")
  @Test
  void commitsCompleteProjection() {
    final RoutingPolicyProjectionResult result =
        projectionService.project(fixture().toByteArray());

    assertThat(result.duplicate()).isFalse();
    assertThat(result.routingPolicyId()).isEqualTo(POLICY_ID);
    assertThat(count("risk_service.routing_policies")).isEqualTo(1);
    assertThat(count("risk_service.routing_policy_assignments")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT active FROM risk_service.routing_policies", Boolean.class))
        .isTrue();
    final RoutingPolicyProjection restored =
        repository.findApplicable(LocalDate.of(2026, 7, 27), NOW).orElseThrow();
    assertThat(restored.partitionFor(new RoutingInstrument("AAPL", "XTAI"))).isEqualTo(7);
  }

  @DisplayName("equivalent policy replay is idempotent and keeps one active projection")
  @Test
  void duplicateProjectionIsIdempotent() {
    final RoutingPolicyProjectionResult first =
        projectionService.project(fixture().toByteArray());
    final RoutingPolicyProjectionResult duplicate =
        projectionService.project(fixture().toByteArray());

    assertThat(duplicate.routingPolicyId()).isEqualTo(first.routingPolicyId());
    assertThat(duplicate.duplicate()).isTrue();
    assertThat(count("risk_service.routing_policies")).isEqualTo(1);
    assertThat(count("risk_service.routing_policy_assignments")).isEqualTo(2);
  }

  @DisplayName("a process restart rehydrates the active policy and assignment set")
  @Test
  void restoresProjectionFromDurableState() {
    projectionService.project(fixture().toByteArray());

    final RoutingPolicyProjectionRepository restartedRepository =
        new JdbcRoutingPolicyProjectionRepository(jdbcTemplate);
    final RoutingPolicyProjection restored =
        restartedRepository.findApplicable(LocalDate.of(2026, 7, 27), NOW).orElseThrow();

    assertThat(restored.identity().routingPolicyId()).isEqualTo(POLICY_ID);
    assertThat(restored.partitionFor(new RoutingInstrument("TSLA", "ROCO"))).isEqualTo(11);
  }

  @DisplayName("activation failure rolls back the staged policy and assignments")
  @Test
  void rollsBackWhenActivationFails() {
    repository.failNextActivation();

    assertThatThrownBy(() -> projectionService.project(fixture().toByteArray()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated activation failure");
    assertThat(count("risk_service.routing_policies")).isZero();
    assertThat(count("risk_service.routing_policy_assignments")).isZero();
  }

  private RoutingPolicy fixture() {
    return RoutingPolicy.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c02")
                .setCreatedAtUnixMs(1_753_176_000_000L)
                .setSourceService("marketdata-publisher")
                .setCorrelationId(POLICY_ID.toString())
                .build())
        .setRoutingPolicyId(POLICY_ID.toString())
        .setSourceMarketSnapshotId(SNAPSHOT_ID.toString())
        .setTradingDay(TradingDay.newBuilder().setIsoDate("2026-07-27").build())
        .setEffectiveFromUnixMs(Instant.parse("2026-07-27T00:00:00Z").toEpochMilli())
        .setEffectiveUntilUnixMs(Instant.parse("2026-07-27T06:00:00Z").toEpochMilli())
        .setOrdersValidatedPartitionCount(16)
        .addAssignments(assignment("TSLA", "ROCO", 11))
        .addAssignments(assignment("AAPL", "XTAI", 7))
        .build();
  }

  private InstrumentRoutingAssignment assignment(String symbol, String venueMic, int partition) {
    return InstrumentRoutingAssignment.newBuilder()
        .setInstrument(
            VenueInstrument.newBuilder().setSymbol(symbol).setVenueMic(venueMic).build())
        .setRoutingPartition(partition)
        .build();
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
      dataSource.setUrl(riskServiceUrl("routingprojection"));
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/risk-service")
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
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
      final TransactionTemplate template = new TransactionTemplate(manager);
      template.setTimeout(8);
      return template;
    }

    @Bean
    RoutingPolicyProjectionRepository routingPolicyProjectionRepository(JdbcTemplate jdbcTemplate) {
      return new TestRoutingPolicyProjectionRepository(
          new JdbcRoutingPolicyProjectionRepository(jdbcTemplate));
    }

    @Bean
    RoutingPolicyProjectionService routingPolicyProjectionService(
        RoutingPolicyProjectionRepository repository, TransactionTemplate transactionTemplate) {
      return new RoutingPolicyProjectionService(
          repository,
          transactionTemplate,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }

  static final class TestRoutingPolicyProjectionRepository
      implements RoutingPolicyProjectionRepository {
    private final RoutingPolicyProjectionRepository delegate;
    private boolean failNextActivation;

    TestRoutingPolicyProjectionRepository(RoutingPolicyProjectionRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public java.util.Optional<RoutingPolicyProjection> findById(UUID routingPolicyId) {
      return delegate.findById(routingPolicyId);
    }

    @Override
    public java.util.Optional<RoutingPolicyProjection> findApplicable(
        LocalDate tradingDay, Instant at) {
      return delegate.findApplicable(tradingDay, at);
    }

    @Override
    public java.util.Optional<RoutingPolicyProjection> findLatestActive() {
      return delegate.findLatestActive();
    }

    @Override
    public void insertStaged(RoutingPolicyProjection projection, Instant receivedAt) {
      delegate.insertStaged(projection, receivedAt);
    }

    @Override
    public void activate(UUID routingPolicyId) {
      if (failNextActivation) {
        failNextActivation = false;
        throw new IllegalStateException("simulated activation failure");
      }
      delegate.activate(routingPolicyId);
    }

    void failNextActivation() {
      failNextActivation = true;
    }

    void clearFailures() {
      failNextActivation = false;
    }
  }
}
