package com.simplematch.quickfixgateway.wal;

import java.util.Objects;

/** Persists one inbound command and its unresolved state before Risk submission. */
public final class WalDurableCommandWriter {
  private final WalAppender walAppender;
  private final WalRecoveryJournal recoveryJournal;

  /** Creates the paired command-WAL and recovery-state writer. */
  public WalDurableCommandWriter(
      WalAppender walAppender, WalRecoveryJournal recoveryJournal) {
    this.walAppender = Objects.requireNonNull(walAppender, "walAppender");
    this.recoveryJournal = Objects.requireNonNull(recoveryJournal, "recoveryJournal");
  }

  /** Durably stores the command before marking its outcome as unresolved. */
  public void appendForSubmission(WalRecord walRecord) {
    walAppender.appendAndFlush(walRecord);
    recoveryJournal.appendAndFlush(walRecord.recordId(), WalRecoveryState.UNKNOWN);
  }
}
