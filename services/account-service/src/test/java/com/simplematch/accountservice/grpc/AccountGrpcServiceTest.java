package com.simplematch.accountservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.reservation.IdempotentReservationService;
import com.simplematch.accountservice.reservation.ReservationService;
import com.simplematch.accountservice.store.JdbcReservationRepository;
import com.simplematch.contracts.account.v1.ReserveRequest;
import com.simplematch.contracts.account.v1.ReserveResponse;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
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

  private JdbcTemplate jdbcTemplate;
  private ReservationService reservationService;

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
    reservationService = new IdempotentReservationService(
        new JdbcReservationRepository(jdbcTemplate),
        FIXED_CLOCK);
  }

  @DisplayName("reserve returns the same persisted result when request id repeats")
  @Test
  void reserveReturnsSamePersistedResultWhenRequestIdRepeats() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final ReserveRequest request = ReserveRequest.newBuilder()
        .setRequestId(REQUEST_ID)
        .setOrderId("O-1")
        .setAccountId("ACC-1")
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
    assertThat(firstObserver.value().getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM account_reservations WHERE request_id = ?",
        Integer.class,
        REQUEST_ID)).isEqualTo(1);
  }

  @DisplayName("reserve rejects non-UUID request ids at the gRPC ingress")
  @Test
  void reserveRejectsNonUuidRequestIdsAtGrpcIngress() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setRequestId("cmd-1").build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription()).isEqualTo("request_id must be a UUID");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_reservations", Integer.class)).isEqualTo(0);
  }

  @DisplayName("reserve rejects oversized request ids before persistence")
  @Test
  void reserveRejectsOversizedRequestIdsBeforePersistence() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setRequestId(oversizedIdentifier()).build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("request_id must be <= 255 characters");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_reservations", Integer.class)).isEqualTo(0);
  }

  @DisplayName("reserve rejects oversized order ids before persistence")
  @Test
  void reserveRejectsOversizedOrderIdsBeforePersistence() {
    final AccountGrpcService service = new AccountGrpcService(reservationService);
    final TestStreamObserver<ReserveResponse> observer = new TestStreamObserver<>();

    service.reserve(validReserveRequest().setOrderId(oversizedIdentifier()).build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(observer.value()).isNull();
    assertThat(Status.fromThrowable(observer.error()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(observer.error()).getDescription())
        .isEqualTo("order_id must be <= 255 characters");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_reservations", Integer.class)).isEqualTo(0);
  }

  private ReserveRequest.Builder validReserveRequest() {
    return ReserveRequest.newBuilder()
        .setRequestId(REQUEST_ID)
        .setOrderId("O-1")
        .setAccountId("ACC-1")
        .setSymbol("AAPL")
        .setSide(Side.SIDE_BUY)
        .setQuantity("10")
        .setLimitPrice("101.25");
  }

  private String oversizedIdentifier() {
    return "X".repeat(256);
  }
}
