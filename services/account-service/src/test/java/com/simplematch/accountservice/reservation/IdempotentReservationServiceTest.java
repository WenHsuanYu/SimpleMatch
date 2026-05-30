package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class IdempotentReservationServiceTest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);

  @Test
  void persistsAcceptedReservationWhenRequestIdIsNew() {
    final RecordingReservationRepository reservationRepository = new RecordingReservationRepository();
    reservationRepository.queueFindResult(Optional.empty());
    final IdempotentReservationService service = new IdempotentReservationService(
        reservationRepository,
        FIXED_CLOCK);

    final ReservationRecord reservation = service.reserve(reserveOperation());

    assertThat(reservation.requestId()).isEqualTo("cmd-1");
    assertThat(reservation.reservationId()).isEqualTo("O-1");
    assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(reservationRepository.insertedReservation).isEqualTo(reservation);
  }

  @Test
  void returnsExistingReservationWithoutInsertingWhenRequestIdRepeats() {
    final RecordingReservationRepository reservationRepository = new RecordingReservationRepository();
    final ReservationRecord existing = ReservationRecord.accepted(reserveOperation(), 99L);
    reservationRepository.queueFindResult(Optional.of(existing));
    final IdempotentReservationService service = new IdempotentReservationService(
        reservationRepository,
        FIXED_CLOCK);

    final ReservationRecord reservation = service.reserve(reserveOperation());

    assertThat(reservation).isEqualTo(existing);
    assertThat(reservationRepository.insertedReservation).isNull();
  }

  @Test
  void returnsExistingReservationWhenInsertHitsDuplicateKey() {
    final RecordingReservationRepository reservationRepository = new RecordingReservationRepository();
    final ReservationRecord existing = ReservationRecord.accepted(reserveOperation(), 100L);
    reservationRepository.queueFindResult(Optional.empty());
    reservationRepository.queueFindResult(Optional.of(existing));
    reservationRepository.failWithDuplicateKey = true;
    final IdempotentReservationService service = new IdempotentReservationService(
        reservationRepository,
        FIXED_CLOCK);

    final ReservationRecord reservation = service.reserve(reserveOperation());

    assertThat(reservation).isEqualTo(existing);
  }

  @Test
  void rethrowsDuplicateKeyWhenRequestIdCannotBeResolvedAfterConflict() {
    final RecordingReservationRepository reservationRepository = new RecordingReservationRepository();
    reservationRepository.queueFindResult(Optional.empty());
    reservationRepository.queueFindResult(Optional.empty());
    reservationRepository.failWithDuplicateKey = true;
    final IdempotentReservationService service = new IdempotentReservationService(
        reservationRepository,
        FIXED_CLOCK);

    assertThatThrownBy(() -> service.reserve(reserveOperation()))
        .isInstanceOf(DuplicateKeyException.class);
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

  private static final class RecordingReservationRepository implements ReservationRepository {
    private final ArrayDeque<Optional<ReservationRecord>> findResults = new ArrayDeque<>();
    private ReservationRecord insertedReservation;
    private boolean failWithDuplicateKey;

    private void queueFindResult(Optional<ReservationRecord> result) {
      findResults.addLast(result);
    }

    @Override
    public Optional<ReservationRecord> findByRequestId(String requestId) {
      return findResults.isEmpty() ? Optional.empty() : findResults.removeFirst();
    }

    @Override
    public void insert(ReservationRecord reservation) {
      if (failWithDuplicateKey) {
        throw new DuplicateKeyException("duplicate");
      }
      insertedReservation = reservation;
    }
  }
}