package com.simplematch.config.delivery;

/** Persistence port for rebuildable projection dead-letter diagnostics. */
public interface DeadLetterStore {
  /** Persists one diagnostic dead-letter record. */
  void save(DeadLetterEvidence evidence);
}
