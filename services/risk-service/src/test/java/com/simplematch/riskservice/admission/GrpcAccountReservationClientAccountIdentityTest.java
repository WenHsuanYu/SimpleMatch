package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.grpc.AccountReservationV2GrpcService;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.config.GrpcProperties;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GrpcAccountReservationClientAccountIdentityTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC);

  @DisplayName("Risk preserves the canonical account UUID through Account persistence")
  @Test
  void preservesCanonicalAccountIdentityAcrossGrpcBoundary() throws Exception {
    final DriverManagerDataSource dataSource = accountDataSource();
    migrateAccountService(dataSource);
    final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    final AccountReservationApplicationService accounts = accountService(jdbc);
    final UUID accountId = UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13");
    accounts.provisionLimit(
        accountId.toString(), LocalDate.of(2026, 7, 28), new BigDecimal("10000"));

    final Server server =
        ServerBuilder.forPort(0)
            .addService(new AccountReservationV2GrpcService(accounts))
            .build()
            .start();
    try (GrpcAccountReservationClient client = reservationClient(server.getPort())) {
      final AdmissionCommand command = command(accountId);

      final ReservationOutcome outcome = client.reserve(command);
      final UUID persistedAccountId =
          jdbc.queryForObject(
              "SELECT account_id FROM account_service.account_reservations WHERE order_id = ?",
              (rows, row) -> rows.getObject("account_id", UUID.class),
              command.identity().orderId().value().toString());
      final String outboxMessageKey =
          jdbc.queryForObject(
              "SELECT message_key FROM account_service.outbox WHERE aggregate_id = ?",
              String.class,
              command.identity().orderId().value().toString());

      assertThat(outcome.accepted()).isTrue();
      assertThat(outcome.reservationId()).isEqualTo(command.identity().orderId().value());
      assertThat(persistedAccountId).isEqualTo(accountId);
      assertThat(outboxMessageKey).isEqualTo(accountId.toString());
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private DriverManagerDataSource accountDataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:account-identity-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
    return dataSource;
  }

  private void migrateAccountService(DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
  }

  private AccountReservationApplicationService accountService(JdbcTemplate jdbc) {
    return new AccountReservationApplicationService(
        new JdbcAccountAuthorityReader(jdbc),
        new JdbcAccountAuthorityLifecycleWriter(jdbc),
        new JdbcAccountOutboxRepository(jdbc),
        CLOCK);
  }

  private GrpcAccountReservationClient reservationClient(int port) {
    final GrpcProperties properties =
        new GrpcProperties(
            new GrpcProperties.GrpcTargetsProperties("localhost:" + port, "localhost:0"));
    return new GrpcAccountReservationClient(properties);
  }

  private AdmissionCommand command(UUID accountId) {
    return new AdmissionCommand(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(
                UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11")),
            new AdmissionIdentity.OrderId(
                UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c12")),
            new AdmissionIdentity.AccountId(accountId)),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol("2330"), new AdmissionOrder.VenueMic("XTAI")),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode("SIDE_BUY"),
                new AdmissionOrder.Quantity(10),
                new AdmissionOrder.LimitPriceUnits(1_000_000L),
                new AdmissionOrder.OrderTypeCode("ORDER_TYPE_LIMIT"),
                new AdmissionOrder.TimeInForceCode("TIME_IN_FORCE_ROD")),
            LocalDate.of(2026, 7, 28)),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("CLIENT1"),
            new AdmissionFixIdentity.TargetCompId("SIMPLEMATCH"),
            new AdmissionFixIdentity.ClOrdId("C1")),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(
                UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c15"))));
  }
}
