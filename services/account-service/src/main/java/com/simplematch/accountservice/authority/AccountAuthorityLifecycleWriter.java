package com.simplematch.accountservice.authority;

/** Persists idempotency claims and authoritative account lifecycle transitions. */
public interface AccountAuthorityLifecycleWriter {
  /** Serializes concurrent attempts to create the same reservation request. */
  void claimReservationRequest(String requestId, long claimedAtUnixMs);

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
  boolean claimInbox(
      String consumerName,
      String eventId,
      String aggregateId,
      Long aggregateSequence,
      long receivedAt);
}
