package com.simplematch.contracts.matching.runtime.v1;

/** Signals that one final event identity was observed with different exact Kafka value bytes. */
public final class DeterministicEventConflictException extends IllegalStateException {
  /** Creates a conflict carrying the identity required for quarantine and market interruption. */
  public DeterministicEventConflictException(String eventId) {
    super("matching event identity has conflicting payload bytes: " + eventId);
  }
}
