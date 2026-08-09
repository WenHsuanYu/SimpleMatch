package com.simplematch.accountservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.contracts.account.v1.ApplyFillRequest;
import com.simplematch.contracts.account.v1.ApplyFillResponse;
import com.simplematch.contracts.account.v1.GetLimitsRequest;
import com.simplematch.contracts.account.v1.GetLimitsResponse;
import com.simplematch.contracts.account.v1.GetPositionsRequest;
import com.simplematch.contracts.account.v1.GetPositionsResponse;
import com.simplematch.contracts.account.v1.ReleaseReservationRequest;
import com.simplematch.contracts.account.v1.ReleaseReservationResponse;
import com.simplematch.contracts.account.v1.ReserveRequest;
import com.simplematch.contracts.account.v1.ReserveResponse;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
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

class AccountGrpcServiceTest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);
  private static final String REQUEST_ID = "01971cbe-0f5a-7c69-9d6c-8e7f6a5b4c3d";
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  private JdbcTemplate jdbcTemplate;
  private AccountReservationApplicationService reservationService;

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
    final AccountReservationApplicationService accountService =
        new AccountReservationApplicationService(
            new JdbcAccountAuthorityReader(jdbcTemplate),
            new JdbcAccountAuthorityLifecycleWriter(jdbcTemplate),
            new JdbcAccountOutboxRepository(jdbcTemplate),
            FIXED_CLOCK);
    accountService.provisionLimit(
        ACCOUNT_ID, LocalDate.of(1970, 1, 1), new BigDecimal("10000"));
    reservationService = accountService;
  }

  @DisplayName("reserve returns the same persisted result when request id repeats")
  @Test
  void reserveReturnsSamePersistedResultWhenRequestIdRepeats() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final ReserveRequest request =
        ReserveRequest.newBuilder()
            .setRequestId(REQUEST_ID)
            .setOrderId("O-1")
            .setAccountId(ACCOUNT_ID)
            .setSymbol("AAPL")
            .setSide(Side.SIDE_BUY)
            .setQuantity("10")
            .setLimitPrice("101.25")
            .build();
    final TestStreamObserver<ReserveResponse> firstObserver = new TestStreamObserver<>();
    final TestStreamObserver<ReserveResponse> secondObserver = new TestStreamObserver<>();

    service.reserve(request, firstObserver);
    service.reserve(request, secondObserver);

    assertThat(firstObserver.completed()).isTrue();
    assertThat(firstObserver.error()).isNull();
    assertThat(secondObserver.completed()).isTrue();
    assertThat(secondObserver.error()).isNull();
    assertThat(secondObserver.value()).isEqualTo(firstObserver.value());
    assertThat(firstObserver.value().getRequestId()).isEqualTo(REQUEST_ID);
    assertThat(firstObserver.value().getReservationId()).isEqualTo("O-1");
    assertThat(firstObserver.value().getStatus())
        .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations WHERE request_id = ?",
                Integer.class,
                REQUEST_ID))
        .isEqualTo(1);
  }

  @DisplayName("reserve rejects non-UUID request ids at the gRPC ingress")
  @Test
  void reserveRejectsNonUuidRequestIdsAtGrpcIngress() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setRequestId("cmd-1").build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("request_id must be a UUID");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isEqualTo(0);
  }

  @DisplayName("reserve rejects non-UUID account ids at the gRPC ingress")
  @Test
  void reserveRejectsNonUuidAccountIdsAtGrpcIngress() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setAccountId("ACC-1").build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("account_id must be a UUID");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isEqualTo(0);
  }

  @DisplayName("reserve rejects oversized request ids before persistence")
  @Test
  void reserveRejectsOversizedRequestIdsBeforePersistence() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setRequestId(oversizedIdentifier()).build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("request_id must be <= 255 characters");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations", Integer.class))
        .isEqualTo(0);
  }

  @DisplayName("reserve rejects oversized order ids before persistence")
  @Test
  void reserveRejectsOversizedOrderIdsBeforePersistence() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setOrderId(oversizedIdentifier()).build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("order_id must be <= 255 characters");
    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_reservations", Integer.class))
        .isEqualTo(0);
  }

  @DisplayName("read RPCs project authoritative limits and positions")
  @Test
  void readRpcsProjectAuthoritativeState() {
    reservationService.provisionPosition(ACCOUNT_ID, "AAPL");
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<GetLimitsResponse> limitsObserver = new TestStreamObserver<>();
    final TestStreamObserver<GetPositionsResponse> positionsObserver = new TestStreamObserver<>();

    service.getLimits(
        GetLimitsRequest.newBuilder().setAccountId(ACCOUNT_ID).build(), limitsObserver);
    service.getPositions(
        GetPositionsRequest.newBuilder().setAccountId(ACCOUNT_ID).build(), positionsObserver);

    assertThat(limitsObserver.error()).isNull();
    assertThat(limitsObserver.completed()).isTrue();
    assertThat(limitsObserver.value().getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(limitsObserver.value().getCurrency()).isEqualTo("TWD");
    assertThat(new BigDecimal(limitsObserver.value().getAvailableNotional()))
        .isEqualByComparingTo("10000");
    assertThat(new BigDecimal(limitsObserver.value().getReservedNotional()))
        .isEqualByComparingTo("0");
    assertThat(new BigDecimal(limitsObserver.value().getUtilizedNotional()))
        .isEqualByComparingTo("0");
    assertThat(positionsObserver.error()).isNull();
    assertThat(positionsObserver.completed()).isTrue();
    assertThat(positionsObserver.value().getPositionsCount()).isEqualTo(1);
    assertThat(positionsObserver.value().getPositions(0).getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(positionsObserver.value().getPositions(0).getSymbol()).isEqualTo("AAPL");
    assertThat(new BigDecimal(positionsObserver.value().getPositions(0).getLongQty()))
        .isEqualByComparingTo("0");
    assertThat(new BigDecimal(positionsObserver.value().getPositions(0).getShortQty()))
        .isEqualByComparingTo("0");
  }

  @DisplayName("release RPC maps the request identity and terminal reservation state")
  @Test
  void releaseReservationProjectsReleasedState() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final ReserveResponse reserved = reserveAccepted(service);
    final TestStreamObserver<ReleaseReservationResponse> observer = new TestStreamObserver<>();

    service.releaseReservation(
        ReleaseReservationRequest.newBuilder()
            .setRequestId(REQUEST_ID)
            .setReservationId(reserved.getReservationId())
            .setOrderId("O-1")
            .setReasonCode("USER_CANCELLED")
            .build(),
        observer);

    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    assertThat(observer.value().getRequestId()).isEqualTo(REQUEST_ID);
    assertThat(observer.value().getReservationId()).isEqualTo(reserved.getReservationId());
    assertThat(observer.value().getStatus())
        .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
  }

  @DisplayName("fill RPC maps the execution identity and applied reservation state")
  @Test
  void applyFillProjectsAppliedState() {
    reservationService.provisionPosition(ACCOUNT_ID, "AAPL");
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final ReserveResponse reserved = reserveAccepted(service);
    final TestStreamObserver<ApplyFillResponse> observer = new TestStreamObserver<>();

    service.applyFill(
        ApplyFillRequest.newBuilder()
            .setRequestId(REQUEST_ID)
            .setReservationId(reserved.getReservationId())
            .setOrderId("O-1")
            .setExecId(UUID.randomUUID().toString())
            .setFillQty("10")
            .setFillPx("101.25")
            .build(),
        observer);

    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    assertThat(observer.value().getRequestId()).isEqualTo(REQUEST_ID);
    assertThat(observer.value().getReservationId()).isEqualTo(reserved.getReservationId());
    assertThat(observer.value().getStatus())
        .isEqualTo(ReservationStatus.RESERVATION_STATUS_APPLIED);
  }

  private ReserveResponse reserveAccepted(AccountGrpcService service) {
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();
    service.reserve(validReserveRequest().build(), observer);
    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    return observer.value();
  }

  private ReserveRequest.Builder validReserveRequest() {
    return ReserveRequest.newBuilder()
        .setRequestId(REQUEST_ID)
        .setOrderId("O-1")
        .setAccountId(ACCOUNT_ID)
        .setSymbol("AAPL")
        .setSide(Side.SIDE_BUY)
        .setQuantity("10")
        .setLimitPrice("101.25");
  }

  private String oversizedIdentifier() {
    return "X".repeat(256);
  }
}
