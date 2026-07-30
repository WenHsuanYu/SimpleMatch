package com.simplematch.marketdatapublisher.publication;

/** Checked failure from a local snapshot publication dependency that must roll back the outcome. */
public final class SnapshotPublicationFailure extends Exception {
  /** Creates a failure with the local persistence detail. */
  public SnapshotPublicationFailure(String message) {
    super(message);
  }
}
