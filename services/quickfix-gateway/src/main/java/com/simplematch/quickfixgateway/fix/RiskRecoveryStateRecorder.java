package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
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

  void record(OrderCommand command, WalRecord walRecord, RiskSubmissionResult submission) {
    try {
      recoveryJournal.appendAndFlush(
          command.getCommandId(), WalRecoveryState.fromSubmission(submission));
    } catch (RuntimeException failure) {
      logger.error(
          "failed to persist WAL recovery state command_id={} order_id={} outcome={}",
          command.getCommandId(),
          walRecord.orderId(),
          submission.outcome(),
          failure);
    }
  }
}
