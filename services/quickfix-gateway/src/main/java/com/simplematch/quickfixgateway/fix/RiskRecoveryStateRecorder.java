package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalRecord;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
import com.simplematch.quickfixgateway.wal.WalRecoveryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists best-effort local recovery knowledge without changing authoritative Risk outcomes. */
final class RiskRecoveryStateRecorder {
  private static final Logger logger = LoggerFactory.getLogger(RiskRecoveryStateRecorder.class);

  private final WalRecoveryJournal recoveryJournal;

  RiskRecoveryStateRecorder(WalRecoveryJournal recoveryJournal) {
    this.recoveryJournal = recoveryJournal;
  }

  void record(WalRecord walRecord, RiskSubmissionResult submission) {
    try {
      recoveryJournal.appendAndFlush(
          walRecord.recordId(), WalRecoveryState.fromSubmission(submission));
    } catch (RuntimeException failure) {
      logger.error(
          "failed to persist WAL recovery state command_id={} order_id={} outcome={}",
          walRecord.recordId(),
          walRecord.orderId(),
          submission.outcome(),
          failure);
    }
  }
}
