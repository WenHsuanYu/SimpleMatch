package com.simplematch.accountservice.authority;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Reads authoritative account state, including rows that a caller has locked for mutation. */
public interface AccountAuthorityReader {
  /** Locks the account limit row for one trading day. */
  Optional<AccountLimit> findLimitForUpdate(String accountId, LocalDate tradingDay);

  /** Reads the account limit without taking a mutation lock. */
  Optional<AccountLimit> findLimit(String accountId, LocalDate tradingDay);

  /** Locks one position row for mutation. */
  Optional<AccountPosition> findPositionForUpdate(String accountId, String symbol);

  /** Finds a position without a lock for a read response. */
  Optional<AccountPosition> findPosition(String accountId, String symbol);

  /** Lists positions for one account. */
  List<AccountPosition> findPositions(String accountId);

  /** Finds an existing reservation by request id. */
  Optional<AccountReservation> findReservationByRequestId(String requestId);

  /** Locks an existing reservation by its reservation id. */
  Optional<AccountReservation> findReservationForUpdate(String reservationId);
}
