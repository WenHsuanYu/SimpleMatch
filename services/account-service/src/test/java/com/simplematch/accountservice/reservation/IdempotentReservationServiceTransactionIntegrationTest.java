package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig(IdempotentReservationServiceTransactionIntegrationTest.TestConfiguration.class)
class IdempotentReservationServiceTransactionIntegrationTest {
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);

  @Autowired
  private ReservationService reservationService;

  @Autowired
  private TrackingReservationRepository trackingReservationRepository;

  @Test
  void reserveRunsInsideTransactionalProxy() {
    final ReservationRecord reservation = reservationService.reserve(reserveOperation());

    assertThat(AopUtils.isAopProxy(reservationService)).isTrue();
    assertThat(trackingReservationRepository.findCalledInTransaction()).isTrue();
    assertThat(trackingReservationRepository.insertCalledInTransaction()).isTrue();
    assertThat(trackingReservationRepository.insertedReservation()).isEqualTo(reservation);
    assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
  }

  private static ReserveOperation reserveOperation() {
    return new ReserveOperation(
        "cmd-1",
        "O-1",
        "ACC-1",
        "AAPL",
        Side.SIDE_BUY,
        new BigDecimal("10"),
        new BigDecimal("101.25"));
  }

  @Configuration
  @EnableTransactionManagement
  static class TestConfiguration {
    @Bean
    DataSource dataSource() {
      final DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.h2.Driver");
      dataSource.setUrl("jdbc:h2:mem:account-service-transaction-test;DB_CLOSE_DELAY=-1");
      return dataSource;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    Clock clock() {
      return FIXED_CLOCK;
    }

    @Bean
    TrackingReservationRepository trackingReservationRepository() {
      return new TrackingReservationRepository();
    }

    @Bean
    ReservationService reservationService(
        TrackingReservationRepository reservationRepository,
        Clock clock) {
      return new IdempotentReservationService(reservationRepository, clock);
    }
  }

  static final class TrackingReservationRepository implements ReservationRepository {
    private boolean findCalledInTransaction;
    private boolean insertCalledInTransaction;
    private ReservationRecord insertedReservation;

    @Override
    public Optional<ReservationRecord> findByRequestId(String requestId) {
      findCalledInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
      return Optional.empty();
    }

    @Override
    public void insert(ReservationRecord reservation) {
      insertCalledInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
      insertedReservation = reservation;
    }

    boolean findCalledInTransaction() {
      return findCalledInTransaction;
    }

    boolean insertCalledInTransaction() {
      return insertCalledInTransaction;
    }

    ReservationRecord insertedReservation() {
      return insertedReservation;
    }
  }
}