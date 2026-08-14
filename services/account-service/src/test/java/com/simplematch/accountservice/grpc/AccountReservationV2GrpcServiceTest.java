package com.simplematch.accountservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.AccountReservationInvariantException;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.account.v2.ReservationAction;
import com.simplematch.contracts.account.v2.ReservationCommand;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.common.v2.TwdPrice;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.ShareQuantity;
import io.grpc.Status;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountReservationV2GrpcServiceTest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);
  private static final String COMMAND_ID = "01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d";
  private static final String ORDER_ID = "01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3e";
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  private JdbcTemplate jdbcTemplate;
  private AccountReservationV2GrpcService service;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
    jdbcTemplate = new JdbcTemplate(dataSource);
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
    final AccountReservationApplicationService reservationService =
        new AccountReservationApplicationService(
            new JdbcAccountAuthorityReader(jdbcTemplate),
            new JdbcAccountAuthorityLifecycleWriter(jdbcTemplate),
            new JdbcAccountOutboxRepository(jdbcTemplate),
            FIXED_CLOCK);
    reservationService.provisionLimit(
        ACCOUNT_ID, LocalDate.of(1970, 1, 1), new BigDecimal("10000"));
    service = new AccountReservationV2GrpcService(reservationService);
  }

  @DisplayName("v2 reserve returns a typed lifecycle outcome and replays equivalent requests")
  @Test
  void reserveReturnsTypedOutcomeAndReplaysEquivalentRequest() {
    final ReservationCommand request = validRequest();
    final TestStreamObserver<AccountLifecycleEvent> first = new TestStreamObserver<>();
    final TestStreamObserver<AccountLifecycleEvent> second = new TestStreamObserver<>();

    service.reserve(request, first);
    service.reserve(request, second);

    assertThat(first.error()).isNull();
    assertThat(first.completed()).isTrue();
    assertThat(second.error()).isNull();
    assertThat(second.completed()).isTrue();
    assertThat(second.value()).isEqualTo(first.value());
    assertThat(first.value().getState())
        .isEqualTo(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED);
    assertThat(first.value().getReservationId()).isEqualTo(ORDER_ID);
    assertThat(first.value().getReservedNotional().getUnits()).isEqualTo(10_125_000L);
    assertThat(first.value().getReservedQuantity().getShares()).isEqualTo(10L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isEqualTo(1);
  }

  @DisplayName("v2 reserve distinguishes conflicting command reuse")
  @Test
  void reserveRejectsConflictingCommandReuse() {
    service.reserve(validRequest(), new TestStreamObserver<>());
    final ReservationCommand conflicting =
        validRequest().toBuilder()
            .setQuantity(ShareQuantity.newBuilder().setShares(11).build())
            .setNotional(TwdNotional.newBuilder().setUnits(11_137_500L).build())
            .build();
    final TestStreamObserver<AccountLifecycleEvent> observer = new TestStreamObserver<>();

    service.reserve(conflicting, observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.ALREADY_EXISTS);
  }

  @DisplayName("v2 reserve treats a changed venue as a request conflict")
  @Test
  void reserveRejectsChangedVenueForTheSameCommand() {
    service.reserve(validRequest(), new TestStreamObserver<>());
    final ReservationCommand conflicting =
        validRequest()
            .toBuilder()
            .setInstrument(VenueInstrument.newBuilder().setSymbol("2330").setVenueMic("XTAI2"))
            .build();
    final TestStreamObserver<AccountLifecycleEvent> observer = new TestStreamObserver<>();

    service.reserve(conflicting, observer);

    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT venue_mic FROM account_service.account_reservations", String.class))
        .isEqualTo("XTAI");
  }

  @DisplayName("v2 reserve rejects non-reserve actions before persistence")
  @Test
  void reserveRejectsUnsupportedAction() {
    final TestStreamObserver<AccountLifecycleEvent> observer = new TestStreamObserver<>();

    service.reserve(
        validRequest().toBuilder()
            .setAction(ReservationAction.RESERVATION_ACTION_RELEASE)
            .build(),
        observer);

    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isZero();
  }

  @DisplayName("v2 reserve rejects malformed event metadata before persistence")
  @Test
  void reserveRejectsMalformedEventMetadata() {
    final TestStreamObserver<AccountLifecycleEvent> observer = new TestStreamObserver<>();
    final ReservationCommand malformed =
        validRequest()
            .toBuilder()
            .setMetadata(
                validRequest().getMetadata().toBuilder().setEventId("not-a-uuid").build())
            .build();

    service.reserve(malformed, observer);

    assertThat(observer.error()).isNotNull();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isZero();
  }

  @DisplayName("v2 reserve reports Account invariant failures as failed precondition")
  @Test
  void reserveReportsInvariantFailureAsFailedPrecondition() {
    final AccountReservationApplicationService failingService =
        mock(AccountReservationApplicationService.class);
    when(failingService.reserve(any(ReserveOperation.class)))
        .thenThrow(new AccountReservationInvariantException("optimistic version conflict"));
    final AccountReservationV2GrpcService grpcService =
        new AccountReservationV2GrpcService(failingService);
    final TestStreamObserver<AccountLifecycleEvent> observer = new TestStreamObserver<>();

    grpcService.reserve(validRequest(), observer);

    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("account reservation invariant failed");
  }

  @DisplayName("v2 reserve reports unexpected state failures as internal errors")
  @Test
  void reserveReportsUnexpectedStateAsInternal() {
    final AccountReservationApplicationService failingService =
        mock(AccountReservationApplicationService.class);
    when(failingService.reserve(any(ReserveOperation.class)))
        .thenThrow(new IllegalStateException("unexpected state"));
    final AccountReservationV2GrpcService grpcService =
        new AccountReservationV2GrpcService(failingService);
    final TestStreamObserver<AccountLifecycleEvent> observer = new TestStreamObserver<>();

    grpcService.reserve(validRequest(), observer);

    assertThat(Status.fromThrowable(observer.error()).getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("failed to persist reservation");
  }

  private ReservationCommand validRequest() {
    return ReservationCommand.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId("01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3f")
                .setCreatedAtUnixMs(100L)
                .setSourceService("risk-service")
                .setCorrelationId(COMMAND_ID)
                .build())
        .setReservationId(ORDER_ID)
        .setCommandId(COMMAND_ID)
        .setOrderId(ORDER_ID)
        .setAccountId(ACCOUNT_ID)
        .setAction(ReservationAction.RESERVATION_ACTION_RESERVE)
        .setInstrument(VenueInstrument.newBuilder().setSymbol("2330").setVenueMic("XTAI"))
        .setSide(Side.SIDE_BUY)
        .setQuantity(ShareQuantity.newBuilder().setShares(10))
        .setLimitPrice(TwdPrice.newBuilder().setUnits(1_012_500))
        .setNotional(TwdNotional.newBuilder().setUnits(10_125_000))
        .build();
  }
}
