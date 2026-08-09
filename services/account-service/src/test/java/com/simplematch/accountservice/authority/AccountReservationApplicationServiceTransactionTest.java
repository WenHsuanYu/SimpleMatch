package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.accountservice.reservation.AccountMatchingExecutionApplicationService;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.ApplyFillOperation;
import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationRequestIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
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
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";
  private final JdbcTemplate jdbcTemplate;
  private final AccountReservationApplicationService service;
  private final AccountMatchingExecutionApplicationService matchingExecutionService;
  private final FailingOutboxRepository outboxRepository;

  AccountReservationApplicationServiceTransactionTest(
      JdbcTemplate jdbcTemplate,
      AccountReservationApplicationService service,
      AccountMatchingExecutionApplicationService matchingExecutionService,
      FailingOutboxRepository outboxRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.service = service;
    this.matchingExecutionService = matchingExecutionService;
    this.outboxRepository = outboxRepository;
  }

  @BeforeEach
  void reset() {
    outboxRepository.clearFailure();
    jdbcTemplate.update("DELETE FROM account_service.inbox");
    jdbcTemplate.update("DELETE FROM account_service.reservation_request_locks");
    jdbcTemplate.update("DELETE FROM account_service.outbox");
    jdbcTemplate.update("DELETE FROM account_service.account_reservations");
    jdbcTemplate.update("DELETE FROM account_service.account_positions");
    jdbcTemplate.update("DELETE FROM account_service.account_limits");
    service.provisionLimit(ACCOUNT_ID, TRADING_DAY, new BigDecimal("10000"));
    service.provisionPosition(ACCOUNT_ID, "2330");
    jdbcTemplate.update(
        "UPDATE account_service.account_positions SET long_qty = 100 WHERE account_id = ?",
        UUID.fromString(ACCOUNT_ID));
  }

  @DisplayName("outbox failure rolls back reservation and account-authority mutations")
  @Test
  void rollsBackReserveWhenOutboxFails() {
    outboxRepository.failInserts();

    assertThatThrownBy(() -> service.reserve(operation(Side.SIDE_BUY, "10", "100")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("account lifecycle outbox is unavailable");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.outbox", Integer.class))
        .isZero();
    assertThat(service.getLimits(ACCOUNT_ID).availableNotional()).isEqualByComparingTo("10000");
  }

  @DisplayName("buy reserve atomically consumes available cash and emits lifecycle outbox")
  @Test
  void reservesCashAndEmitsEvent() throws InvalidProtocolBufferException {
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
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT topic FROM account_service.outbox", String.class))
        .isEqualTo("account.lifecycle");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT message_key FROM account_service.outbox", String.class))
        .isEqualTo(reservation.accountId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload_type FROM account_service.outbox", String.class))
        .isEqualTo(AccountLifecycleEvent.getDescriptor().getFullName());
    final AccountLifecycleEvent event =
        AccountLifecycleEvent.parseFrom(
            jdbcTemplate.queryForObject(
                "SELECT payload FROM account_service.outbox", byte[].class));
    assertThat(event.getMetadata().getSchemaVersion()).isEqualTo("v2");
    assertThat(event.getReservationId()).isEqualTo(reservation.reservationId());
    assertThat(event.getOrderId()).isEqualTo(reservation.orderId());
    assertThat(event.getAccountId()).isEqualTo(reservation.accountId());
    assertThat(event.getState())
        .isEqualTo(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED);
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
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM account_service.account_reservations",
                BigDecimal.class))
        .isEqualByComparingTo("101");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reserved_notional FROM account_service.account_reservations",
                BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT filled_quantity FROM account_service.account_reservations", BigDecimal.class))
        .isEqualByComparingTo("0");
  }

  @DisplayName("rejected reservations cannot accept execution fills")
  @Test
  void rejectsFillForRejectedReservation() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "101", "100"));
    final ApplyFillOperation fill =
        new ApplyFillOperation(
            new ReservationIdentity(
                new ReservationIdentity.RequestId(reservation.requestId()),
                new ReservationIdentity.ReservationId(reservation.reservationId()),
                new ReservationIdentity.OrderId(reservation.orderId())),
            new ExecutionFill(
                new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
                ExecutionFill.AggregateSequence.absent(),
                new ExecutionFill.FillQuantity(new BigDecimal("1")),
                new ExecutionFill.FillPrice(new BigDecimal("99"))));

    assertThatThrownBy(() -> service.applyFill(fill))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reservation is not active");
    assertThat(service.getLimits(ACCOUNT_ID).availableNotional()).isEqualByComparingTo("10000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM account_service.account_reservations", String.class))
        .isEqualTo(ReservationStatus.RESERVATION_STATUS_REJECTED.name());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.inbox", Integer.class))
        .isEqualTo(0);
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

  @DisplayName("duplicate aggregate sequence delivery is a harmless no-op")
  @Test
  void deduplicatesAggregateSequenceDelivery() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));
    final ReservationIdentity identity =
        new ReservationIdentity(
            new ReservationIdentity.RequestId(reservation.requestId()),
            new ReservationIdentity.ReservationId(reservation.reservationId()),
            new ReservationIdentity.OrderId(reservation.orderId()));
    final ExecutionFill firstFill =
        new ExecutionFill(
            new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
            new ExecutionFill.AggregateSequence(7L),
            new ExecutionFill.FillQuantity(new BigDecimal("4")),
            new ExecutionFill.FillPrice(new BigDecimal("99")));
    final ApplyFillOperation first = new ApplyFillOperation(identity, firstFill);
    final ApplyFillOperation duplicateSequence =
        new ApplyFillOperation(
            identity,
            new ExecutionFill(
                new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
                new ExecutionFill.AggregateSequence(7L),
                new ExecutionFill.FillQuantity(new BigDecimal("4")),
                new ExecutionFill.FillPrice(new BigDecimal("99"))));

    final ReservationRecord applied = service.applyFill(first);
    final ReservationRecord duplicate = service.applyFill(duplicateSequence);

    assertThat(applied.reservedNotional()).isEqualByComparingTo("600");
    assertThat(duplicate.reservedNotional()).isEqualByComparingTo("600");
    assertThat(service.getLimits(ACCOUNT_ID).utilizedNotional()).isEqualByComparingTo("396");
    assertThat(service.getLimits(ACCOUNT_ID).availableNotional()).isEqualByComparingTo("9004");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.inbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("partial buy fill returns price improvement and keeps remaining authority")
  @Test
  void partialBuyFillReturnsPriceImprovement() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));
    final ApplyFillOperation fill =
        new ApplyFillOperation(
            new ReservationIdentity(
                new ReservationIdentity.RequestId(reservation.requestId()),
                new ReservationIdentity.ReservationId(reservation.reservationId()),
                new ReservationIdentity.OrderId(reservation.orderId())),
            new ExecutionFill(
                new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
                ExecutionFill.AggregateSequence.absent(),
                new ExecutionFill.FillQuantity(new BigDecimal("4")),
                new ExecutionFill.FillPrice(new BigDecimal("99"))));

    final ReservationRecord applied = service.applyFill(fill);
    final AccountLimit limitAfterFill = service.getLimits(ACCOUNT_ID);
    final ReservationRecord duplicate = service.applyFill(fill);

    assertThat(applied.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(applied.reservedNotional()).isEqualByComparingTo("600");
    assertThat(limitAfterFill.reservedNotional()).isEqualByComparingTo("600");
    assertThat(limitAfterFill.utilizedNotional()).isEqualByComparingTo("396");
    assertThat(limitAfterFill.availableNotional()).isEqualByComparingTo("9004");
    assertThat(duplicate.reservedNotional()).isEqualByComparingTo("600");
    assertThat(service.getLimits(ACCOUNT_ID).availableNotional()).isEqualByComparingTo("9004");
  }

  @DisplayName("matching execution fills use the public account boundary and inbox deduplication")
  @Test
  void appliesMatchingFillOnceThroughAccountBoundary() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));
    final ExecutionEvent event =
        matchingEvent(
            reservation,
            "00000000-0000-0000-0000-000000000101",
            ExecutionType.EXECUTION_TYPE_PARTIAL_FILL,
            "4",
            "99");

    final ReservationRecord applied = matchingExecutionService.applyMatchingExecution(event);
    final ReservationRecord duplicate = matchingExecutionService.applyMatchingExecution(event);

    assertThat(applied.reservedNotional()).isEqualByComparingTo("600");
    assertThat(duplicate.reservedNotional()).isEqualByComparingTo("600");
    assertThat(service.getLimits(ACCOUNT_ID).utilizedNotional()).isEqualByComparingTo("396");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.inbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("matching terminal events release remaining authority exactly once")
  @Test
  void appliesMatchingCancellationOnceThroughAccountBoundary() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));
    final ExecutionEvent event =
        matchingEvent(
            reservation,
            "00000000-0000-0000-0000-000000000102",
            ExecutionType.EXECUTION_TYPE_CANCELED,
            "",
            "");

    final ReservationRecord released = matchingExecutionService.applyMatchingExecution(event);
    final ReservationRecord duplicate = matchingExecutionService.applyMatchingExecution(event);

    assertThat(released.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
    assertThat(duplicate.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
    assertThat(service.getLimits(ACCOUNT_ID).availableNotional()).isEqualByComparingTo("10000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.inbox", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("release after partial fill returns only the remaining authority")
  @Test
  void releasesOnlyRemainingAuthorityAfterPartialFill() {
    final ReservationRecord reservation = service.reserve(operation(Side.SIDE_BUY, "10", "100"));
    final ReservationIdentity identity =
        new ReservationIdentity(
            new ReservationIdentity.RequestId(reservation.requestId()),
            new ReservationIdentity.ReservationId(reservation.reservationId()),
            new ReservationIdentity.OrderId(reservation.orderId()));
    service.applyFill(
        new ApplyFillOperation(
            identity,
            new ExecutionFill(
                new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
                ExecutionFill.AggregateSequence.absent(),
                new ExecutionFill.FillQuantity(new BigDecimal("4")),
                new ExecutionFill.FillPrice(new BigDecimal("99")))));

    final ReservationRecord released =
        service.release(
            new ReleaseReservationOperation(
                identity, new ReleaseReservationOperation.ReleaseReason("IOC_REMAINDER")));

    assertThat(released.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM account_service.account_reservations",
                BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT filled_quantity FROM account_service.account_reservations", BigDecimal.class))
        .isEqualByComparingTo("4");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reserved_notional FROM account_service.account_reservations",
                BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(service.getLimits(ACCOUNT_ID).reservedNotional()).isEqualByComparingTo("0");
    assertThat(service.getLimits(ACCOUNT_ID).utilizedNotional()).isEqualByComparingTo("396");
    assertThat(service.getLimits(ACCOUNT_ID).availableNotional()).isEqualByComparingTo("9604");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.outbox", Integer.class))
        .isEqualTo(3);
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
            new ReservationRequestIdentity.AccountId(ACCOUNT_ID)),
        new ReservationTerms(
            new ReservationTerms.InstrumentSymbol("2330"),
            side,
            new ReservationTerms.ReservationQuantity(new BigDecimal(quantity)),
            new ReservationTerms.LimitPrice(new BigDecimal(price))));
  }

  private ExecutionEvent matchingEvent(
      ReservationRecord reservation,
      String executionId,
      ExecutionType executionType,
      String fillQuantity,
      String fillPrice) {
    return ExecutionEvent.newBuilder()
        .setExecId(executionId)
        .setOrderId(reservation.orderId())
        .setAccountId(reservation.accountId())
        .setSymbol(reservation.symbol())
        .setExecutionType(executionType)
        .setFillQty(fillQuantity)
        .setFillPx(fillPrice)
        .setText("matching-terminal")
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
    AccountAuthorityReader authorityReader(JdbcTemplate jdbcTemplate) {
      return new JdbcAccountAuthorityReader(jdbcTemplate);
    }

    @Bean
    AccountAuthorityLifecycleWriter authorityLifecycleWriter(JdbcTemplate jdbcTemplate) {
      return new JdbcAccountAuthorityLifecycleWriter(jdbcTemplate);
    }

    @Bean
    FailingOutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) {
      return new FailingOutboxRepository(jdbcTemplate);
    }

    @Bean
    AccountReservationApplicationService reservationService(
        AccountAuthorityReader authorityReader,
        AccountAuthorityLifecycleWriter authorityLifecycleWriter,
        AccountOutboxRepository outboxRepository,
        Clock clock) {
      return new AccountReservationApplicationService(
          authorityReader, authorityLifecycleWriter, outboxRepository, clock);
    }

    @Bean
    AccountMatchingExecutionApplicationService matchingExecutionService(
        AccountAuthorityReader authorityReader,
        AccountReservationApplicationService reservationService) {
      return new AccountMatchingExecutionApplicationService(authorityReader, reservationService);
    }
  }

  static final class FailingOutboxRepository implements AccountOutboxRepository {
    private final JdbcAccountOutboxRepository delegate;
    private boolean failInserts;

    FailingOutboxRepository(JdbcTemplate jdbcTemplate) {
      delegate = new JdbcAccountOutboxRepository(jdbcTemplate);
    }

    @Override
    public void insert(AccountLifecycleOutbox event) {
      if (failInserts) {
        throw new IllegalStateException("account lifecycle outbox is unavailable");
      }
      delegate.insert(event);
    }

    void failInserts() {
      failInserts = true;
    }

    void clearFailure() {
      failInserts = false;
    }
  }
}
