package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.ApplyFillOperation;
import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationRequestIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/** Integration tests for the account authority's atomic reserve and lifecycle seam. */
@SpringJUnitConfig(AccountReservationApplicationServiceTransactionTest.TestConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AccountReservationApplicationServiceTransactionTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 28);
  private final JdbcTemplate jdbcTemplate;
  private final AccountReservationApplicationService service;

  AccountReservationApplicationServiceTransactionTest(
      JdbcTemplate jdbcTemplate, AccountReservationApplicationService service) {
    this.jdbcTemplate = jdbcTemplate;
    this.service = service;
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM account_service.inbox");
    jdbcTemplate.update("DELETE FROM account_service.reservation_request_locks");
    jdbcTemplate.update("DELETE FROM account_service.outbox");
    jdbcTemplate.update("DELETE FROM account_service.account_reservations");
    jdbcTemplate.update("DELETE FROM account_service.account_positions");
    jdbcTemplate.update("DELETE FROM account_service.account_limits");
    service.provisionLimit("acc-1", TRADING_DAY, new BigDecimal("10000"));
    service.provisionPosition("acc-1", "2330");
    jdbcTemplate.update(
        "UPDATE account_service.account_positions SET long_qty = 100 WHERE account_id = 'acc-1'");
  }

  @DisplayName("buy reserve atomically consumes available cash and emits lifecycle outbox")
  @Test
  void reservesCashAndEmitsEvent() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));

    assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(reservation.reservedNotional()).isEqualByComparingTo("1000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT available_notional FROM account_service.account_limits", BigDecimal.class))
        .isEqualByComparingTo("9000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.outbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("insufficient cash persists a stable rejection without consuming account authority")
  @Test
  void rejectsWithoutPartialBalanceMutation() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "101", "100"));

    assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_REJECTED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT available_notional FROM account_service.account_limits", BigDecimal.class))
        .isEqualByComparingTo("10000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.outbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("sell reserve consumes available long position and replays duplicate request")
  @Test
  void reservesPositionAndDeduplicatesRequest() {
    final ReserveOperation operation = operation(Side.SIDE_SELL, "10", "100");

    final ReservationRecord first = service.reserve(operation);
    final ReservationRecord duplicate = service.reserve(operation);

    assertThat(duplicate.requestId()).isEqualTo(first.requestId());
    assertThat(duplicate.reservationId()).isEqualTo(first.reservationId());
    assertThat(duplicate.status()).isEqualTo(first.status());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reserved_long_qty FROM account_service.account_positions",
                BigDecimal.class))
        .isEqualByComparingTo("10");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("duplicate execution events are harmless and only fill the reservation once")
  @Test
  void deduplicatesExecutionAndReleasesCashOnFill() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));
    final String executionId = UUID.randomUUID().toString();

    final ApplyFillOperation fill =
        new ApplyFillOperation(
            new ReservationIdentity(
                new ReservationIdentity.RequestId(reservation.requestId()),
                new ReservationIdentity.ReservationId(reservation.reservationId()),
                new ReservationIdentity.OrderId(reservation.orderId())),
            new ExecutionFill(
                new ExecutionFill.ExecutionId(executionId),
                ExecutionFill.AggregateSequence.absent(),
                new ExecutionFill.FillQuantity(new BigDecimal("10")),
                new ExecutionFill.FillPrice(new BigDecimal("99"))));

    final ReservationRecord applied = service.applyFill(fill);
    final ReservationRecord duplicate = service.applyFill(fill);

    assertThat(applied.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_APPLIED);
    assertThat(duplicate.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_APPLIED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reserved_notional FROM account_service.account_reservations",
                BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT utilized_notional FROM account_service.account_limits", BigDecimal.class))
        .isEqualByComparingTo("990");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.inbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("release returns remaining authority and is idempotent")
  @Test
  void releasesRemainingCashOnce() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));

    final ReleaseReservationOperation release =
        new ReleaseReservationOperation(
            new ReservationIdentity(
                new ReservationIdentity.RequestId(reservation.requestId()),
                new ReservationIdentity.ReservationId(reservation.reservationId()),
                new ReservationIdentity.OrderId(reservation.orderId())),
            new ReleaseReservationOperation.ReleaseReason("IOC_REMAINDER"));

    final ReservationRecord released = service.release(release);
    final ReservationRecord duplicate = service.release(release);

    assertThat(released.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
    assertThat(duplicate.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT available_notional FROM account_service.account_limits", BigDecimal.class))
        .isEqualByComparingTo("10000");
  }

  @DisplayName("concurrent reservations cannot overspend the same cash limit")
  @Test
  void serializesConcurrentCashReservations()
      throws InterruptedException, ExecutionException, TimeoutException {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<ReservationRecord> first =
          executor.submit(() -> service.reserve(operation(Side.SIDE_BUY, "100", "100")));
      final Future<ReservationRecord> second =
          executor.submit(() -> service.reserve(operation(Side.SIDE_BUY, "100", "100")));

      final ReservationStatus firstStatus = first.get(5, TimeUnit.SECONDS).status();
      final ReservationStatus secondStatus = second.get(5, TimeUnit.SECONDS).status();

      assertThat(java.util.Set.of(firstStatus, secondStatus))
          .containsExactlyInAnyOrder(
              ReservationStatus.RESERVATION_STATUS_ACCEPTED,
              ReservationStatus.RESERVATION_STATUS_REJECTED);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT available_notional FROM account_service.account_limits",
                  BigDecimal.class))
          .isEqualByComparingTo("0");
    } finally {
      executor.shutdownNow();
    }
  }

  private ReserveOperation operation(Side side, String quantity, String price) {
    return new ReserveOperation(
        new ReservationRequestIdentity(
            new ReservationRequestIdentity.RequestId(UUID.randomUUID().toString()),
            new ReservationRequestIdentity.OrderId(UUID.randomUUID().toString()),
            new ReservationRequestIdentity.AccountId("acc-1")),
        new ReservationTerms(
            new ReservationTerms.InstrumentSymbol("2330"),
            side,
            new ReservationTerms.ReservationQuantity(new BigDecimal(quantity)),
            new ReservationTerms.LimitPrice(new BigDecimal(price))));
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TestConfiguration {
    @Bean
    DriverManagerDataSource dataSource() {
      final DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.h2.Driver");
      dataSource.setUrl(
          "jdbc:h2:mem:account-authority;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
              + "INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/account-service")
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
    Clock clock() {
      return CLOCK;
    }

    @Bean
    AccountAuthorityRepository authorityRepository(JdbcTemplate jdbcTemplate) {
      return new com.simplematch.accountservice.store.JdbcAccountAuthorityRepository(jdbcTemplate);
    }

    @Bean
    AccountOutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) {
      return new com.simplematch.accountservice.store.JdbcAccountOutboxRepository(jdbcTemplate);
    }

    @Bean
    AccountReservationApplicationService reservationService(
        AccountAuthorityRepository authorityRepository,
        AccountOutboxRepository outboxRepository,
        Clock clock) {
      return new AccountReservationApplicationService(authorityRepository, outboxRepository, clock);
    }
  }
}
