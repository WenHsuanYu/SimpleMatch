package com.simplematch.accountservice.authority;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistence port for account authority rows and lifecycle idempotency. */
@SuppressWarnings("PMD.TooManyMethods") // Authoritative-account port; split only with separate adapters.
public interface AccountAuthorityRepository {
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

  /** Serializes concurrent attempts to create the same reservation request. */
  void claimReservationRequest(String requestId, long claimedAtUnixMs);

  /** Locks an existing reservation by its reservation id. */
  Optional<AccountReservation> findReservationForUpdate(String reservationId);

  /** Inserts a new account limit. */
  void insertLimit(AccountLimit limit);

  /** Inserts a new account position. */
  void insertPosition(AccountPosition position);

  /** Inserts a new reservation. */
  void insertReservation(AccountReservation reservation);

  /** Updates a locked account limit with optimistic version checking. */
  void updateLimit(AccountLimit limit, long expectedVersion);

  /** Updates a locked account position with optimistic version checking. */
  void updatePosition(AccountPosition position, long expectedVersion);

  /** Updates a locked reservation with optimistic version checking. */
  void updateReservation(AccountReservation reservation, long expectedVersion);

  /** Records an inbox event if unseen and returns whether processing may continue. */
  boolean claimInbox(String consumerName, String eventId, String aggregateId, Long aggregateSequence, long receivedAt);
}
