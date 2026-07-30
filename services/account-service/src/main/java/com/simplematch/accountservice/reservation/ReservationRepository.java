package com.simplematch.accountservice.reservation;

import java.util.Optional;

/** Persistence port for account-service reservation ingress state. */
public interface ReservationRepository {
  /**
   * Finds the reservation previously persisted for a synchronous request identifier.
   *
   * @param requestId the synchronous operation identifier exposed on the gRPC boundary
   * @return the stored reservation if the request has already been applied
   */
  Optional<ReservationRecord> findByRequestId(String requestId);

  /**
   * Inserts a new reservation snapshot.
   *
   * @param reservation the reservation snapshot to persist
   */
  void insert(ReservationRecord reservation);
}
