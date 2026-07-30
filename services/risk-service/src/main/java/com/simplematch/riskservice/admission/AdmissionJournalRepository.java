package com.simplematch.riskservice.admission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for durable admission saga state. */
public interface AdmissionJournalRepository {
  /** Finds an admission by command identity. */
  Optional<AdmissionJournalEntry> findByCommandId(UUID commandId);

  /** Finds an equivalent admission by its FIX business identity. */
  Optional<AdmissionJournalEntry> findByBusinessKey(AdmissionCommand command);

  /** Inserts a pending admission row. */
  boolean insert(AdmissionJournalEntry entry);

  /** Updates a pending row to a terminal outcome using optimistic versioning. */
  void update(AdmissionJournalEntry entry, long expectedVersion);

  /** Finds pending rows old enough for bounded recovery. */
  List<AdmissionJournalEntry> findPendingBefore(Instant cutoff, int limit);
}
