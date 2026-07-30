package com.simplematch.riskservice.outbox;

/** Persists outbox records inside the owning business transaction. */
public interface OutboxRepository {
  /** Inserts one outbox record. */
  void insert(OutboxRecord record);
}
