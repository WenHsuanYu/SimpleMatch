package com.simplematch.quickfixgateway.wal;

import com.simplematch.quickfixgateway.risk.RiskReconciliationResult;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;

/** Local recovery knowledge recorded after a durable inbound command is submitted. */
public enum WalRecoveryState {
  UNKNOWN,
  PENDING,
  ACCEPTED,
  REJECTED;

  /** Returns whether no further reconciliation is required for this command. */
  public boolean terminal() {
    return this == ACCEPTED || this == REJECTED;
  }

  /** Maps a gateway submission result to its local recovery state. */
  public static WalRecoveryState fromSubmission(RiskSubmissionResult result) {
    return switch (result.outcome()) {
      case ACCEPTED -> ACCEPTED;
      case REJECTED -> REJECTED;
      case UNKNOWN -> UNKNOWN;
    };
  }

  /** Maps Risk's authoritative reconciliation snapshot to local recovery state. */
  public static WalRecoveryState fromReconciliation(RiskReconciliationResult result) {
    return switch (result.outcome()) {
      case NOT_FOUND -> UNKNOWN;
      case PENDING -> PENDING;
      case ACCEPTED -> ACCEPTED;
      case REJECTED -> REJECTED;
    };
  }
}
