package com.simplematch.riskservice.outbox;

/** Persists outbox records inside the owning business transaction. */
public interface OutboxRepository {
  /** Inserts one outbox record. */
  void insert(OutboxRecord record);

  /**
   * Inserts one stable-identity record unless its event has already been persisted.
   *
   * @return {@code true} when this call inserted the row, or {@code false} for an equivalent replay
   */
  default boolean insertIfAbsent(OutboxRecord record) {
    insert(record);
    return true;
  }
}
