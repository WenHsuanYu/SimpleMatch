package com.simplematch.accountservice.reservation;

/**
 * Service boundary for synchronous reservation writes.
 */
public interface ReservationService {
  /**
   * Persists the reservation represented by the operation or replays the already persisted result.
   *
   * @param operation the validated reservation operation to store
   * @return the persisted reservation snapshot keyed by {@code request_id}
   */
  ReservationRecord reserve(ReserveOperation operation);
}