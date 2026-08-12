package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.grpc.AccountReservationV2GrpcService;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.config.GrpcProperties;
import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.store.JdbcAdmissionJournalRepository;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies Account v2 idempotency across a Risk local transaction failure and recovery. */
class AccountReservationSagaRecoveryIntegrationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 28);
  private static final UUID ACCOUNT_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13");
  private static final UUID COMMAND_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11");
  private static final UUID ORDER_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c12");
  private static final ArtifactIdentity ARTIFACT_IDENTITY =
      new ArtifactIdentity(
          TRADING_DAY, "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943");

  private JdbcTemplate accountJdbc;
  private JdbcTemplate riskJdbc;
  private Server accountServer;
  private GrpcAccountReservationClient accountClient;
  private OrderAdmissionApplicationService admissions;
  private PendingAdmissionRecovery recovery;
  private FailingOutboxRepository riskOutbox;

  @BeforeEach
  void setUp() throws Exception {
    final DriverManagerDataSource accountDataSource =
        dataSource("accountreservationaccount", "account_service");
    migrate(accountDataSource, "account-service");
    accountJdbc = new JdbcTemplate(accountDataSource);
    final AccountReservationApplicationService accounts =
        new AccountReservationApplicationService(
            new JdbcAccountAuthorityReader(accountJdbc),
            new JdbcAccountAuthorityLifecycleWriter(accountJdbc),
            new JdbcAccountOutboxRepository(accountJdbc),
            CLOCK);
    accounts.provisionLimit(ACCOUNT_ID.toString(), TRADING_DAY, new BigDecimal("10000"));
    accountServer =
        ServerBuilder.forPort(0)
            .addService(new AccountReservationV2GrpcService(accounts))
            .build()
            .start();

    final DriverManagerDataSource riskDataSource =
        dataSource("accountreservationrisk", "risk_service");
    migrate(riskDataSource, "risk-service");
    riskJdbc = new JdbcTemplate(riskDataSource);
    final TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(riskDataSource));
    transactionTemplate.setTimeout(8);
    riskOutbox = new FailingOutboxRepository(new JdbcOutboxRepository(riskJdbc));
    final AdmissionLifecycleTransactions lifecycleTransactions =
        new AdmissionLifecycleTransactions(
            new JdbcAdmissionJournalRepository(riskJdbc),
            riskOutbox,
            new AdmissionOutboxFactory("matching.commands", CLOCK),
            (command, at) -> AdmissionDeliveryRoute.assigned(ARTIFACT_IDENTITY, "stable-v2", 7),
            CLOCK,
            transactionTemplate);
    accountClient =
        new GrpcAccountReservationClient(
            new GrpcProperties(
                new GrpcProperties.GrpcTargetsProperties(
                    "localhost:" + accountServer.getPort(), "localhost:0")));
    admissions =
        new OrderAdmissionApplicationService(
            new OrderAdmissionValidator(),
            lifecycleTransactions,
            accountClient,
            new NoopAdmissionBackpressurePolicy());
    recovery =
        new PendingAdmissionRecovery(
            new JdbcAdmissionJournalRepository(riskJdbc),
            accountClient,
            lifecycleTransactions,
            CLOCK);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (accountClient != null) {
      accountClient.close();
    }
    if (accountServer != null) {
      accountServer.shutdownNow();
      assertThat(accountServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @DisplayName("remote success followed by local failure recovers without a second reservation")
  @Test
  void recoversRemoteSuccessAfterRiskFinalizationFailure() {
    final NewOrderCommand command = command();
    riskOutbox.failNextInsert();

    assertThatThrownBy(() -> admissions.admit(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated Risk outbox outage");
    assertThat(riskState()).isEqualTo("PENDING");
    assertThat(count(riskJdbc, "risk_service.outbox")).isZero();
    assertThat(count(accountJdbc, "account_service.account_reservations")).isEqualTo(1);
    assertThat(count(accountJdbc, "account_service.outbox")).isEqualTo(1);

    agePendingAdmission();

    assertThat(recovery.recover()).isEqualTo(1);
    assertThat(riskState()).isEqualTo("ACCEPTED");
    assertThat(count(riskJdbc, "risk_service.outbox")).isEqualTo(1);
    assertThat(count(accountJdbc, "account_service.account_reservations")).isEqualTo(1);
    assertThat(count(accountJdbc, "account_service.outbox")).isEqualTo(1);
    assertThat(accountJdbc.queryForObject(
            "SELECT available_notional FROM account_service.account_limits", BigDecimal.class))
        .isEqualByComparingTo("9000");
    assertThat(
            riskJdbc.queryForObject(
                "SELECT reservation_id FROM risk_service.admission_journal", UUID.class))
        .isEqualTo(ORDER_ID);
    assertThat(recovery.recover()).isZero();
  }

  private void agePendingAdmission() {
    riskJdbc.update(
        "UPDATE risk_service.admission_journal SET created_at_unix_ms = 0, updated_at_unix_ms = 0");
  }

  private String riskState() {
    return riskJdbc.queryForObject("SELECT state FROM risk_service.admission_journal", String.class);
  }

  private int count(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private NewOrderCommand command() {
    return NewOrderCommand.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId(COMMAND_ID.toString())
                .setCreatedAtUnixMs(CLOCK.millis())
                .setSourceService("risk-service")
                .setCorrelationId(COMMAND_ID.toString())
                .build())
        .setCommandId(COMMAND_ID.toString())
        .setOrderId(ORDER_ID.toString())
        .setAccountId(ACCOUNT_ID.toString())
        .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build())
        .setSide(Side.SIDE_BUY)
        .setQuantity(
            com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder().setShares(10).build())
        .setLimitPrice(
            com.simplematch.contracts.common.v2.TwdPrice.newBuilder().setUnits(1_000_000).build())
        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
        .setTif(TimeInForce.TIME_IN_FORCE_ROD)
        .setCurrency(Currency.CURRENCY_TWD)
        .setTradingDay(
            com.simplematch.contracts.common.v2.TradingDay.newBuilder()
                .setIsoDate(TRADING_DAY.toString())
                .build())
        .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
        .setSenderCompId("SENDER")
        .setTargetCompId("TARGET")
        .setClOrdId("CL-1")
        .build();
  }

  private DriverManagerDataSource dataSource(String name, String schema) {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:"
            + name
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS "
            + schema
            + "\\;SET SCHEMA "
            + schema);
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, String service) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/" + service)
        .load()
        .migrate();
  }

  private static final class FailingOutboxRepository implements OutboxRepository {
    private final OutboxRepository delegate;
    private boolean failNext;

    private FailingOutboxRepository(OutboxRepository delegate) {
      this.delegate = delegate;
    }

    private void failNextInsert() {
      failNext = true;
    }

    @Override
    public void insert(OutboxRecord record) {
      if (failNext) {
        failNext = false;
        throw new IllegalStateException("simulated Risk outbox outage");
      }
      delegate.insert(record);
    }
  }
}
