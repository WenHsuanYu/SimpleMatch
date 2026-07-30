package com.simplematch.marketdatapublisher.publication;

/** Signals that another publisher committed a conflicting snapshot version first. */
public final class SnapshotPublicationConflictException extends RuntimeException {
  /**
   * Creates a deterministic conflict result without exposing database-specific constraint details.
   */
  public SnapshotPublicationConflictException() {
    super("a conflicting market snapshot publication already exists");
  }
}
