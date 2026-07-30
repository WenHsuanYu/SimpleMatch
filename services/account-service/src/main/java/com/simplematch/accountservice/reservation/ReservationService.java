package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountPosition;
import java.util.List;

/** Service boundary for synchronous reservation writes. */
public interface ReservationService {
  /**
   * Persists the reservation represented by the operation or replays the already persisted result.
   *
   * @param operation the validated reservation operation to store
   * @return the persisted reservation snapshot keyed by {@code request_id}
   */
  ReservationRecord reserve(ReserveOperation operation);

  /**
   * Returns the current account limit for the active trading day.
   *
   * @param accountId account identifier
   * @return authoritative account limit
   */
  default AccountLimit getLimits(String accountId) {
    throw new UnsupportedOperationException("account limits are not available");
  }

  /**
   * Returns all authoritative positions for an account.
   *
   * @param accountId account identifier
   * @return authoritative positions
   */
  default List<AccountPosition> getPositions(String accountId) {
    throw new UnsupportedOperationException("account positions are not available");
  }

  /**
   * Releases remaining reservation authority idempotently.
   *
   * @param operation the typed reservation identity and release reason
   * @return resulting reservation
   */
  default ReservationRecord release(ReleaseReservationOperation operation) {
    throw new UnsupportedOperationException("reservation release is not available");
  }

  /**
   * Applies one execution fill idempotently.
   *
   * @param operation the validated reservation identity and execution fill
   * @return resulting reservation
   */
  default ReservationRecord applyFill(ApplyFillOperation operation) {
    throw new UnsupportedOperationException("fill application is not available");
  }

}
