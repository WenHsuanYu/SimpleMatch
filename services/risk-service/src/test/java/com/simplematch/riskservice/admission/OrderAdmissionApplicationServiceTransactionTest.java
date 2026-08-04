package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.outbox.RoutingPartitionResolver;
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
  private final AdmissionJournalRepository journal;
  private final TestRoutingPartitionResolver routingResolver;
  private final TransactionTemplate transactionTemplate;

  OrderAdmissionApplicationServiceTransactionTest(
      JdbcTemplate jdbcTemplate,
      OrderAdmissionApplicationService admissions,
      TestAccountReservationClient account,
      Clock clock,
      AdmissionJournalRepository journal,
      TestRoutingPartitionResolver routingResolver,
      TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.admissions = admissions;
    this.account = account;
    this.clock = clock;
    this.journal = journal;
    this.routingResolver = routingResolver;
    this.transactionTemplate = transactionTemplate;
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM risk_service.outbox");
    jdbcTemplate.update("DELETE FROM risk_service.admission_journal");
    account.fail = false;
    routingResolver.reset();
  }

  @DisplayName("begin and finalize commit journal state and one v2 admission outbox event")
  @Test
  void commitsAcceptedAdmissionAtomically() throws Exception {
    final NewOrderCommand command = command();
    final AdmissionResult pending = admissions.beginAdmission(command);
    final AdmissionResult accepted =
        admissions.finalizeAdmission(
            UUID.fromString(command.getCommandId()),
            ReservationOutcome.accepted(UUID.randomUUID()));

    assertThat(pending.decision()).isInstanceOf(AdmissionDecision.Pending.class);
    assertThat(pending.route()).isEqualTo(AdmissionDeliveryRoute.assigned(7));
    assertThat(accepted.decision()).isInstanceOf(AdmissionDecision.AcceptedNew.class);
    assertThat(accepted.route()).isEqualTo(AdmissionDeliveryRoute.assigned(7));
    assertThat(routingResolver.calls).isEqualTo(1);
    assertThat(routingResolver.lastSymbol).isEqualTo("2330");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT state FROM risk_service.admission_journal", String.class))
        .isEqualTo("ACCEPTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT routing_partition FROM risk_service.admission_journal", Integer.class))
        .isEqualTo(7);
    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT message_key FROM risk_service.outbox", String.class))
        .isEqualTo("2330");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kafka_partition_id FROM risk_service.outbox", Integer.class))
        .isEqualTo(7);
    final byte[] payload =
        jdbcTemplate.queryForObject(
            "SELECT payload FROM risk_service.outbox",
            (resultSet, rowNum) -> resultSet.getBytes("payload"));
    assertThat(OrderAdmissionAccepted.parseFrom(payload).getRoutingPartition()).isEqualTo(7);
  }

  @DisplayName("equivalent replay returns the original pending result without a second journal row")
  @Test
  void equivalentReplayIsIdempotent() {
    final NewOrderCommand command = command();

    final AdmissionResult first = admissions.beginAdmission(command);
    final AdmissionResult replay = admissions.beginAdmission(command);

    assertThat(replay).isEqualTo(first);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_service.admission_journal", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("duplicate replay keeps the journaled route after resolver configuration changes")
  @Test
  void duplicateReplayReusesPersistedRoute() {
    final NewOrderCommand command = command();

    final AdmissionResult first = admissions.beginAdmission(command);
    routingResolver.partition = 11;
    final AdmissionResult replay = admissions.beginAdmission(command);

    assertThat(first.route()).isEqualTo(AdmissionDeliveryRoute.assigned(7));
    assertThat(replay.route()).isEqualTo(AdmissionDeliveryRoute.assigned(7));
    assertThat(routingResolver.calls).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT routing_partition FROM risk_service.admission_journal", Integer.class))
        .isEqualTo(7);
  }

  @Test
  void keepsTheAdmissionTransactionModuleDeepAndSmall() {
    assertThat(AdmissionLifecycleTransactions.class.getDeclaredConstructors())
        .hasSize(1)
        .allSatisfy(
            constructor ->
                assertThat(constructor.getParameterCount()).isLessThanOrEqualTo(6));
  }

  @DisplayName("terminal journal and outbox changes roll back together")
  @Test
  void terminalFailureRollsBackJournalUpdate() {
    final NewOrderCommand command = command();
    admissions.beginAdmission(command);
    final AdmissionOutboxFactory events =
        new AdmissionOutboxFactory("orders.validated", clock, routingResolver);
    final OutboxRepository failingOutbox =
        record -> {
          throw new IllegalStateException("simulated outbox outage");
        };
    final AdmissionLifecycleTransactions failingTransactions =
        new AdmissionLifecycleTransactions(
            journal, failingOutbox, events, clock, transactionTemplate);

    assertThatThrownBy(
            () ->
                failingTransactions.finalizeAdmission(
                    UUID.fromString(command.getCommandId()),
                    ReservationOutcome.accepted(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated outbox outage");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT state FROM risk_service.admission_journal", String.class))
        .isEqualTo("PENDING");
    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class))
        .isZero();
  }

  @DisplayName("terminal replay returns the stored result before reading a new outcome")
  @Test
  void terminalReplayRemainsIdempotentWhenOutcomeIsAbsent() {
    final NewOrderCommand command = command();
    admissions.beginAdmission(command);
    final AdmissionResult terminal =
        admissions.finalizeAdmission(
            UUID.fromString(command.getCommandId()),
            ReservationOutcome.accepted(UUID.randomUUID()));

    assertThat(
            admissions.finalizeAdmission(
                UUID.fromString(command.getCommandId()), null))
        .isEqualTo(terminal);
    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("orders for the same symbol share one deterministic delivery route")
  @Test
  void sameSymbolOrdersUseTheSameDeliveryRoute() {
    final NewOrderCommand firstCommand = command();
    final NewOrderCommand secondCommand =
        command().toBuilder()
            .setCommandId(UUID.randomUUID().toString())
            .setOrderId(UUID.randomUUID().toString())
            .setClOrdId("CL-2")
            .build();

    final AdmissionResult first = admissions.beginAdmission(firstCommand);
    final AdmissionResult second = admissions.beginAdmission(secondCommand);
    admissions.finalizeAdmission(
        UUID.fromString(firstCommand.getCommandId()), ReservationOutcome.accepted(UUID.randomUUID()));
    admissions.finalizeAdmission(
        UUID.fromString(secondCommand.getCommandId()),
        ReservationOutcome.accepted(UUID.randomUUID()));

    assertThat(first.route()).isEqualTo(AdmissionDeliveryRoute.assigned(7));
    assertThat(second.route()).isEqualTo(AdmissionDeliveryRoute.assigned(7));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_service.outbox "
                    + "WHERE message_key = '2330' AND kafka_partition_id = 7",
                Integer.class))
        .isEqualTo(2);
    assertThat(routingResolver.calls).isEqualTo(2);
  }

  @DisplayName("JDBC round trip preserves assigned route and terminal decision")
  @Test
  void jdbcRoundTripPreservesAssignedDeliveryRouteAndLifecycleDecision() {
    final AdmissionCommand command = new OrderAdmissionValidator().validate(command());
    final AdmissionDeliveryRoute route = AdmissionDeliveryRoute.assigned(3);
    final AdmissionJournalEntry pending =
        AdmissionJournalEntry.pending(command, route, 100L);

    assertThat(journal.insert(pending)).isTrue();
    final AdmissionJournalEntry loaded =
        journal.findByCommandId(command.identity().commandId().value()).orElseThrow();
    assertThat(loaded.command()).isEqualTo(command);
    assertThat(loaded.route()).isEqualTo(route);

    final AdmissionJournalEntry accepted =
        loaded.finalizeWith(ReservationOutcome.accepted(UUID.randomUUID()), 200L);
    journal.update(accepted, loaded.lifecycle().version());

    final AdmissionJournalEntry terminal =
        journal.findByCommandId(command.identity().commandId().value()).orElseThrow();
    assertThat(terminal.route()).isEqualTo(route);
    assertThat(terminal.lifecycle().decision()).isInstanceOf(AdmissionDecision.AcceptedNew.class);
  }

  @DisplayName("cancel admission returns an explicit accepted-cancel decision")
  @Test
  void cancelAdmissionUsesAcceptedCancelDecision() {
    final AdmissionResult result = admissions.admitCancel(cancelCommand());

    assertThat(result.decision()).isEqualTo(new AdmissionDecision.AcceptedCancel());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reservation_id FROM risk_service.admission_journal", UUID.class))
        .isNull();
  }

  @DisplayName("a different command cannot claim an existing FIX business identity")
  @Test
  void conflictingReplayIsStableConflict() {
    admissions.beginAdmission(command());
    final NewOrderCommand conflicting =
        command().toBuilder().setCommandId(UUID.randomUUID().toString()).build();

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

  @DisplayName(
      "account failure leaves the pending saga retryable and performs no terminal outbox insert")
  @Test
  void accountFailureLeavesPendingSaga() {
    account.fail = true;

    assertThatThrownBy(() -> admissions.admit(command()))
        .isInstanceOf(AdmissionUnavailableException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT state FROM risk_service.admission_journal", String.class))
        .isEqualTo("PENDING");
    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class))
        .isZero();
  }

  @DisplayName("pending admission recovery retries the account call and finalizes once")
  @Test
  void recoversPendingAdmissionAfterAccountOutage() {
    account.fail = true;
    final NewOrderCommand command = command();

    assertThatThrownBy(() -> admissions.admit(command))
        .isInstanceOf(AdmissionUnavailableException.class);
    jdbcTemplate.update(
        "UPDATE risk_service.admission_journal SET created_at_unix_ms = 0, updated_at_unix_ms = 0");

    account.fail = false;
    routingResolver.partition = 11;
    assertThat(admissions.recoverPending()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT state FROM risk_service.admission_journal", String.class))
        .isEqualTo("ACCEPTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT routing_partition FROM risk_service.admission_journal", Integer.class))
        .isEqualTo(7);
    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.outbox", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kafka_partition_id FROM risk_service.outbox", Integer.class))
        .isEqualTo(7);
    assertThat(routingResolver.calls).isEqualTo(1);
    assertThat(admissions.recoverPending()).isZero();
  }

  @DisplayName("transport-independent validation rejects each invalid command shape")
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCommands")
  void validatesCommandTable(String caseName, NewOrderCommand invalid, String reasonCode) {
    assertThatThrownBy(() -> new OrderAdmissionValidator().validate(invalid))
        .isInstanceOf(AdmissionValidationException.class)
        .satisfies(
            error ->
                assertThat(((AdmissionValidationException) error).reasonCode())
                    .isEqualTo(reasonCode));
  }

  @Test
  void preservesCancelTradingDayFailureDetail() {
    final CancelOrderCommand invalid =
        CancelOrderCommand.newBuilder()
            .setCommandId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d")
            .setOrderId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e")
            .setAccountId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
            .setInstrument(
                VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
            .setSide(Side.SIDE_BUY)
            .setOrigClOrdId("ORIG-1")
            .setClOrdId("CL-1")
            .setSenderCompId("SENDER")
            .setTargetCompId("TARGET")
            .setTradingDay(
                com.simplematch.contracts.common.v2.TradingDay.newBuilder().setIsoDate("").build())
            .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
            .build();

    assertThatThrownBy(() -> new OrderAdmissionValidator().validateCancel(invalid))
        .isInstanceOf(AdmissionValidationException.class)
        .hasMessage("INVALID_COMMAND: trading_day must be ISO-8601");
  }

  private static java.util.stream.Stream<Arguments> invalidCommands() {
    final NewOrderCommand base =
        NewOrderCommand.newBuilder()
            .setCommandId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d")
            .setOrderId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e")
            .setAccountId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
            .setInstrument(
                VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
            .setSide(Side.SIDE_BUY)
            .setQuantity(
                com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder()
                    .setShares(10)
                    .build())
            .setLimitPrice(
                com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(100).build())
            .setOrderType(OrderType.ORDER_TYPE_LIMIT)
            .setTif(TimeInForce.TIME_IN_FORCE_ROD)
            .setCurrency(Currency.CURRENCY_TWD)
            .setTradingDay(
                com.simplematch.contracts.common.v2.TradingDay.newBuilder()
                    .setIsoDate("2026-07-28")
                    .build())
            .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
            .setSenderCompId("SENDER")
            .setTargetCompId("TARGET")
            .setClOrdId("CL-1")
            .build();
    return java.util.stream.Stream.of(
        Arguments.of(
            "bad command id", base.toBuilder().setCommandId("bad").build(), "INVALID_COMMAND"),
        Arguments.of(
            "unsupported venue",
            base.toBuilder()
                .setInstrument(
                    VenueInstrument.newBuilder().setVenueMic("XNAS").setSymbol("2330").build())
                .build(),
            "INVALID_INSTRUMENT"),
        Arguments.of(
            "zero quantity",
            base.toBuilder()
                .setQuantity(
                    com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder()
                        .setShares(0)
                        .build())
                .build(),
            "INVALID_COMMAND"),
        Arguments.of(
            "pre-open session",
            base.toBuilder().setSessionState(SessionState.SESSION_STATE_PRE_OPEN).build(),
            "UNSUPPORTED_SESSION"),
        Arguments.of(
            "zero limit",
            base.toBuilder()
                .setLimitPrice(
                    com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(0).build())
                .build(),
            "INVALID_COMMAND"));
  }

  private NewOrderCommand command() {
    return NewOrderCommand.newBuilder()
        .setMetadata(
            com.simplematch.contracts.common.v2.EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId(UUID.randomUUID().toString())
                .setCreatedAtUnixMs(clock.millis())
                .build())
        .setCommandId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d")
        .setOrderId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e")
        .setAccountId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
        .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
        .setSide(Side.SIDE_BUY)
        .setQuantity(
            com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder().setShares(10).build())
        .setLimitPrice(
            com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(1000000).build())
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCurrency(Currency.CURRENCY_TWD)
        .setTradingDay(
            com.simplematch.contracts.common.v2.TradingDay.newBuilder()
                .setIsoDate("2026-07-28")
                .build())
        .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
        .setSenderCompId("SENDER")
        .setTargetCompId("TARGET")
        .setClOrdId("CL-1")
        .build();
  }

  private CancelOrderCommand cancelCommand() {
    return CancelOrderCommand.newBuilder()
        .setCommandId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d")
        .setOrderId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e")
        .setAccountId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
        .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
        .setSide(Side.SIDE_BUY)
        .setOrigClOrdId("ORIG-1")
        .setClOrdId("CXL-1")
        .setSenderCompId("SENDER")
        .setTargetCompId("TARGET")
        .setTradingDay(
            com.simplematch.contracts.common.v2.TradingDay.newBuilder()
                .setIsoDate("2026-07-28")
                .build())
        .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
        .build();
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TestConfiguration {
    @Bean
    DriverManagerDataSource dataSource() {
      final DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.h2.Driver");
      dataSource.setUrl(
          "jdbc:h2:mem:riskadmission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service");
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
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    AdmissionJournalRepository journalRepository(JdbcTemplate jdbcTemplate) {
      return new JdbcAdmissionJournalRepository(jdbcTemplate);
    }

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) {
      return new JdbcOutboxRepository(jdbcTemplate);
    }

    @Bean
    TestAccountReservationClient accountReservationClient() {
      return new TestAccountReservationClient();
    }

    @Bean
    TestRoutingPartitionResolver routingPartitionResolver() {
      return new TestRoutingPartitionResolver();
    }

    @Bean
    AdmissionOutboxFactory admissionOutboxFactory(
        Clock clock, TestRoutingPartitionResolver routingPartitionResolver) {
      return new AdmissionOutboxFactory("orders.validated", clock, routingPartitionResolver);
    }

    @Bean
    AdmissionBackpressurePolicy admissionBackpressurePolicy() {
      return new NoopAdmissionBackpressurePolicy();
    }

    @Bean
    AdmissionLifecycleTransactions admissionLifecycleTransactions(
        AdmissionJournalRepository journal,
        OutboxRepository outbox,
        AdmissionOutboxFactory events,
        Clock clock,
        TransactionTemplate transactionTemplate) {
      return new AdmissionLifecycleTransactions(
          journal, outbox, events, clock, transactionTemplate);
    }

    @Bean
    OrderAdmissionApplicationService admissions(
        AdmissionLifecycleTransactions lifecycleTransactions,
        AdmissionJournalRepository journal,
        TestAccountReservationClient account,
        AdmissionBackpressurePolicy backpressure,
        Clock clock) {
      return new OrderAdmissionApplicationService(
          new OrderAdmissionValidator(),
          lifecycleTransactions,
          journal,
          account,
          backpressure,
          clock);
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

  static final class TestRoutingPartitionResolver implements RoutingPartitionResolver {
    private int partition = 7;
    private int calls;
    private String lastSymbol;

    @Override
    public int resolve(String symbol) {
      calls++;
      lastSymbol = symbol;
      return partition;
    }

    private void reset() {
      partition = 7;
      calls = 0;
      lastSymbol = null;
    }
  }
}
