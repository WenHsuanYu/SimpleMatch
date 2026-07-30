package com.simplematch.accountservice.reservation;

import java.time.Clock;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** Reservation service that replays the stored result for repeated {@code request_id} values. */
@RequiredArgsConstructor
public class IdempotentReservationService implements ReservationService {
  @NonNull private final ReservationRepository reservationRepository;
  @NonNull private final Clock clock;

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
