package com.simplematch.accountservice.reservation;

import java.time.Clock;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** Reservation service that replays the stored result for repeated {@code request_id} values. */
public class IdempotentReservationService implements ReservationService {
  private final ReservationRepository reservationRepository;
  private final Clock clock;

  public IdempotentReservationService(ReservationRepository reservationRepository, Clock clock) {
    this.reservationRepository = Objects.requireNonNull(reservationRepository);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  @Transactional
  public ReservationRecord reserve(ReserveOperation operation) {
    final ReservationRecord existing =
        reservationRepository.findByRequestId(operation.requestId()).orElse(null);
    if (existing != null) {
      return existing;
    }

    final ReservationRecord accepted = ReservationRecord.accepted(operation, clock.millis());
    try {
      reservationRepository.insert(accepted);
      return accepted;
    } catch (DuplicateKeyException duplicateKeyException) {
      return reservationRepository
          .findByRequestId(operation.requestId())
          .orElseThrow(() -> duplicateKeyException);
    }
  }
}
