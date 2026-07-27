package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.store.JdbcAdmissionJournalRepository;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

/** Integration tests for the v2 pending-admission and terminal-event seam. */
@SpringJUnitConfig(OrderAdmissionApplicationServiceTransactionTest.TestConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OrderAdmissionApplicationServiceTransactionTest {
  private final JdbcTemplate jdbcTemplate;
  private final OrderAdmissionApplicationService admissions;
  private final TestAccountReservationClient account;
  private final Clock clock;

  OrderAdmissionApplicationServiceTransactionTest(
      JdbcTemplate jdbcTemplate, OrderAdmissionApplicationService admissions,
      TestAccountReservationClient account, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.admissions = admissions;
    this.account = account;
    this.clock = clock;
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM risk_service.outbox");
    jdbcTemplate.update("DELETE FROM risk_service.admission_journal");
    account.fail = false;
  }

  @DisplayName("begin and finalize commit journal state and one v2 admission outbox event")
  @Test
  void commitsAcceptedAdmissionAtomically() {
    final NewOrderCommand command = command();
    final AdmissionResult pending = admissions.beginAdmission(command);
    final AdmissionResult accepted = admissions.finalizeAdmission(
        UUID.fromString(command.getCommandId()), ReservationOutcome.accepted(UUID.randomUUID()));

    assertThat(pending.state()).isEqualTo(AdmissionState.PENDING);
    assertThat(accepted.state()).isEqualTo(AdmissionState.ACCEPTED);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT state FROM risk_service.admission_journal", String.class)).isEqualTo("ACCEPTED");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class)).isEqualTo(1);
  }

  @DisplayName("equivalent replay returns the original pending result without a second journal row")
  @Test
  void equivalentReplayIsIdempotent() {
    final NewOrderCommand command = command();

    final AdmissionResult first = admissions.beginAdmission(command);
    final AdmissionResult replay = admissions.beginAdmission(command);

    assertThat(replay).isEqualTo(first);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.admission_journal", Integer.class)).isEqualTo(1);
  }

  @DisplayName("a different command cannot claim an existing FIX business identity")
  @Test
  void conflictingReplayIsStableConflict() {
    admissions.beginAdmission(command());
    final NewOrderCommand conflicting = command().toBuilder()
        .setCommandId(UUID.randomUUID().toString()).build();

    assertThatThrownBy(() -> admissions.beginAdmission(conflicting))
        .isInstanceOf(AdmissionConflictException.class);
  }

  @DisplayName("same command identity with changed content is a stable conflict")
  @Test
  void changedSameCommandIsConflict() {
    final NewOrderCommand original = command();
    admissions.beginAdmission(original);
    final NewOrderCommand changed = original.toBuilder().setClOrdId("CL-CHANGED").build();

    assertThatThrownBy(() -> admissions.beginAdmission(changed))
        .isInstanceOf(AdmissionConflictException.class);
  }

  @DisplayName("account failure leaves the pending saga retryable and performs no terminal outbox insert")
  @Test
  void accountFailureLeavesPendingSaga() {
    account.fail = true;

    assertThatThrownBy(() -> admissions.admit(command()))
        .isInstanceOf(AdmissionUnavailableException.class);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT state FROM risk_service.admission_journal", String.class)).isEqualTo("PENDING");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class)).isZero();
  }

  @DisplayName("transport-independent validation rejects each invalid command shape")
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCommands")
  void validatesCommandTable(String caseName, NewOrderCommand invalid, String reasonCode) {
    assertThatThrownBy(() -> new OrderAdmissionValidator().validate(invalid))
        .isInstanceOf(AdmissionValidationException.class)
        .satisfies(error -> assertThat(((AdmissionValidationException) error).reasonCode()).isEqualTo(reasonCode));
  }

  private static java.util.stream.Stream<Arguments> invalidCommands() {
    final NewOrderCommand base = NewOrderCommand.newBuilder()
        .setCommandId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d")
        .setOrderId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e")
        .setAccountId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
        .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
        .setSide(Side.SIDE_BUY).setQuantity(com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder().setShares(10).build())
        .setLimitPrice(com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(100).build())
        .setOrderType(OrderType.ORDER_TYPE_LIMIT).setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCurrency(Currency.CURRENCY_TWD).setTradingDay(com.simplematch.contracts.common.v2.TradingDay.newBuilder()
            .setIsoDate("2026-07-28").build()).setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
        .setSenderCompId("SENDER").setTargetCompId("TARGET").setClOrdId("CL-1").build();
    return java.util.stream.Stream.of(
        Arguments.of("bad command id", base.toBuilder().setCommandId("bad").build(), "INVALID_COMMAND"),
        Arguments.of("unsupported venue", base.toBuilder().setInstrument(
            VenueInstrument.newBuilder().setVenueMic("XNAS").setSymbol("2330").build()).build(), "INVALID_INSTRUMENT"),
        Arguments.of("zero quantity", base.toBuilder().setQuantity(
            com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder().setShares(0).build()).build(), "INVALID_COMMAND"),
        Arguments.of("pre-open session", base.toBuilder().setSessionState(SessionState.SESSION_STATE_PRE_OPEN).build(),
            "UNSUPPORTED_SESSION"),
        Arguments.of("zero limit", base.toBuilder().setLimitPrice(
            com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(0).build()).build(), "INVALID_COMMAND"));
  }

  private NewOrderCommand command() {
    return NewOrderCommand.newBuilder()
        .setMetadata(com.simplematch.contracts.common.v2.EventMetadata.newBuilder().setSchemaVersion("v2")
            .setEventId(UUID.randomUUID().toString()).setCreatedAtUnixMs(clock.millis()).build())
        .setCommandId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d")
        .setOrderId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e")
        .setAccountId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
        .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
        .setSide(Side.SIDE_BUY).setQuantity(com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder().setShares(10).build())
        .setLimitPrice(com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(1000000).build())
        .setOrderType(OrderType.ORDER_TYPE_LIMIT).setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCurrency(Currency.CURRENCY_TWD).setTradingDay(com.simplematch.contracts.common.v2.TradingDay.newBuilder()
            .setIsoDate("2026-07-28").build()).setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
        .setSenderCompId("SENDER").setTargetCompId("TARGET").setClOrdId("CL-1").build();
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TestConfiguration {
    @Bean
    DriverManagerDataSource dataSource() {
      final DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.h2.Driver");
      dataSource.setUrl("jdbc:h2:mem:risk-admission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service");
      Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/risk-service").load().migrate();
      return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) { return new JdbcTemplate(dataSource); }

    @Bean
    PlatformTransactionManager transactionManager(DriverManagerDataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }

    @Bean
    Clock clock() { return Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC); }

    @Bean
    AdmissionJournalRepository journalRepository(JdbcTemplate jdbcTemplate) {
      return new JdbcAdmissionJournalRepository(jdbcTemplate);
    }

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) { return new JdbcOutboxRepository(jdbcTemplate); }

    @Bean
    TestAccountReservationClient accountReservationClient() { return new TestAccountReservationClient(); }

    @Bean
    AdmissionOutboxFactory admissionOutboxFactory(Clock clock) { return new AdmissionOutboxFactory("orders.validated", clock); }

    @Bean
    AdmissionBackpressurePolicy admissionBackpressurePolicy() { return new NoopAdmissionBackpressurePolicy(); }

    @Bean
    OrderAdmissionApplicationService admissions(
        AdmissionJournalRepository journal, OutboxRepository outbox, TestAccountReservationClient account,
        AdmissionOutboxFactory events, AdmissionBackpressurePolicy backpressure, Clock clock,
        TransactionTemplate transactionTemplate) {
      return new OrderAdmissionApplicationService(new OrderAdmissionValidator(), journal, outbox, account,
          events, backpressure, clock, transactionTemplate);
    }
  }

  static final class TestAccountReservationClient implements AccountReservationClient {
    private boolean fail;

    @Override
    public ReservationOutcome reserve(AdmissionCommand command) {
      if (fail) {
        throw new IllegalStateException("simulated account outage");
      }
      return ReservationOutcome.accepted(UUID.randomUUID());
    }
  }
}
