package com.simplematch.quickfixgateway.wal;

import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.risk.RiskCommandSubmitter;
import com.simplematch.quickfixgateway.risk.RiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.RiskReconciliationResult;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;

/** Reconciles locally durable gateway commands with authoritative Risk state at startup. */
public final class WalReplayService {
  private static final Logger logger = LoggerFactory.getLogger(WalReplayService.class);
  private static final String FIX_BEGIN_STRING = "FIX.4.4";

  private final WalAppender walAppender;
  private final Path recoveryJournalPath;
  private final RiskCommandSubmitter riskCommandSubmitter;
  private final RiskReconciliationClient reconciliationClient;
  private final OrderSessionRegistry orderSessionRegistry;

  /** Creates startup recovery over immutable commands, local state, and Risk authority. */
  public WalReplayService(
      WalAppender walAppender,
      WalRecoveryJournal recoveryJournal,
      RiskCommandSubmitter riskCommandSubmitter,
      RiskReconciliationClient reconciliationClient,
      OrderSessionRegistry orderSessionRegistry) {
    this.walAppender = walAppender;
    this.recoveryJournalPath = recoveryJournal.path();
    this.riskCommandSubmitter = riskCommandSubmitter;
    this.reconciliationClient = reconciliationClient;
    this.orderSessionRegistry = orderSessionRegistry;
  }

  /**
   * Creates recovery for callers that cannot reconcile already uncertain remote outcomes.
   *
   * <p>A WAL record without sidecar state can still be submitted because the write ordering proves
   * that Risk submission had not started. Records already marked UNKNOWN or PENDING require the
   * production reconciliation client.
   */
  public WalReplayService(WalAppender walAppender, RiskCommandSubmitter riskCommandSubmitter) {
    this(
        walAppender,
        new WalRecoveryJournal(WalRecoveryJournal.pathFor(walAppender.walPath())),
        riskCommandSubmitter,
        reconciliationRequired(),
        new OrderSessionRegistry());
  }

  /** Reconciles non-terminal durable commands and returns the number of WAL records examined. */
  public int replayAll() {
    final WalRecoveryJournal recoveryJournal = new WalRecoveryJournal(recoveryJournalPath);
    final Map<String, WalRecoveryState> localStates = recoveryJournal.readLatest();
    final List<WalRecord> records = walAppender.readAll();
    int recovered = 0;
    for (WalRecord walRecord : records) {
      final WalRecoveryState localState = localStates.get(walRecord.recordId());
      if (localState != null && localState.terminal()) {
        restoreSessionContext(walRecord, localState);
        continue;
      }
      if (localState == null) {
        recoveryJournal.appendAndFlush(walRecord.recordId(), WalRecoveryState.UNKNOWN);
        submitAndRecord(recoveryJournal, walRecord);
      } else {
        reconcile(recoveryJournal, walRecord, localState);
      }
      recovered += 1;
    }
    logger.info(
        "recovered {} non-terminal WAL records from {} using state journal {}",
        recovered,
        walAppender.walPath(),
        recoveryJournalPath);
    return records.size();
  }

  private void reconcile(
      WalRecoveryJournal recoveryJournal, WalRecord walRecord, WalRecoveryState localState) {
    final String commandId = walRecord.recordId();
    final RiskReconciliationResult authoritative = reconciliationClient.lookup(commandId);
    if (authoritative.notFound()) {
      recoverNotFound(recoveryJournal, walRecord, localState);
      return;
    }
    final WalRecoveryState state = WalRecoveryState.fromReconciliation(authoritative);
    recoveryJournal.appendAndFlush(commandId, state);
    restoreSessionContext(walRecord, state);
  }

  private void recoverNotFound(
      WalRecoveryJournal recoveryJournal, WalRecord walRecord, WalRecoveryState localState) {
    requireSafeResubmission(walRecord, localState);
    submitAndRecord(recoveryJournal, walRecord);
  }

  private void requireSafeResubmission(WalRecord walRecord, WalRecoveryState localState) {
    if (localState == WalRecoveryState.PENDING) {
      throw new IllegalStateException(
          "Risk lost a locally pending admission: " + walRecord.recordId());
    }
    if (localState != WalRecoveryState.UNKNOWN) {
      throw new IllegalStateException(
          "unexpected local recovery state for missing Risk admission: "
              + walRecord.recordId()
              + " state="
              + localState);
    }
  }

  private void submitAndRecord(WalRecoveryJournal recoveryJournal, WalRecord walRecord) {
    final RiskSubmissionResult submission = submitToRisk(walRecord);
    final WalRecoveryState recoveredState = WalRecoveryState.fromSubmission(submission);
    recoveryJournal.appendAndFlush(walRecord.recordId(), recoveredState);
    if (recoveredState == WalRecoveryState.UNKNOWN) {
      throw new IllegalStateException(
          "Risk outcome remains unknown after WAL recovery: " + walRecord.recordId());
    }
    restoreSessionContext(walRecord, recoveredState);
  }

  private RiskSubmissionResult submitToRisk(WalRecord walRecord) {
    return switch (walRecord.command()) {
      case WalCommand.NewOrder ignored -> riskCommandSubmitter.submitNewOrder(walRecord);
      case WalCommand.Cancel ignored -> riskCommandSubmitter.submitCancel(walRecord);
    };
  }

  private void restoreSessionContext(WalRecord walRecord, WalRecoveryState state) {
    if (!(walRecord.command() instanceof WalCommand.NewOrder)
        || state == WalRecoveryState.REJECTED
        || state == WalRecoveryState.UNKNOWN) {
      return;
    }
    final String senderCompId = walRecord.targetCompId();
    final String targetCompId = walRecord.senderCompId();
    final SessionID sessionId = new SessionID(FIX_BEGIN_STRING, senderCompId, targetCompId);
    orderSessionRegistry.registerAcceptedOrder(sessionId, walRecord, 'A');
  }

  private static RiskReconciliationClient reconciliationRequired() {
    return commandId -> {
      throw new IllegalStateException(
          "reconciliation client is required for uncertain WAL command: " + commandId);
    };
  }
}
