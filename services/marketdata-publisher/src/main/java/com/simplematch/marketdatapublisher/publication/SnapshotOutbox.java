package com.simplematch.marketdatapublisher.publication;

/** Inserts the publication event inside the same local transaction as snapshot state. */
public interface SnapshotOutbox {
  /** Persists one binary event for a committed immutable snapshot version. */
  void insert(SnapshotOutboxRecord record) throws SnapshotPublicationFailure;
}
