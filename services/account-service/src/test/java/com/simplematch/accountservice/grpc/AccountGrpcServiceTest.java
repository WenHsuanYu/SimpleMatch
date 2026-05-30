package com.simplematch.accountservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.reservation.IdempotentReservationService;
import com.simplematch.accountservice.reservation.ReservationService;
import com.simplematch.accountservice.store.JdbcReservationRepository;
import com.simplematch.contracts.account.v1.ReserveRequest;
import com.simplematch.contracts.account.v1.ReserveResponse;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
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
        .baselineOnMigrate(true)
        .baselineVersion("1")
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
        .setRequestId("cmd-1")
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
    assertThat(firstObserver.value().getRequestId()).isEqualTo("cmd-1");
    assertThat(firstObserver.value().getReservationId()).isEqualTo("O-1");
    assertThat(firstObserver.value().getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM account_reservations WHERE request_id = ?",
        Integer.class,
        "cmd-1")).isEqualTo(1);
  }
}