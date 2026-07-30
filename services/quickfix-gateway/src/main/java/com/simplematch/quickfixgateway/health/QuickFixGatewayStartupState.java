package com.simplematch.quickfixgateway.health;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Tracks readiness-relevant startup state for the quickfix-gateway owner process. */
public final class QuickFixGatewayStartupState {
  /** Startup phases surfaced to readiness checks and operator diagnostics. */
  public enum Phase {
    STARTING,
    RECOVERING,
    READY,
    FAILED
  }

  private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.STARTING);
  private final AtomicInteger replayedWalRecords = new AtomicInteger();

  private volatile String failureMessage = "";

  /** Returns the current gateway startup phase. */
  public Phase phase() {
    return phase.get();
  }

  /** Returns the number of WAL records replayed by the latest successful recovery. */
  public int replayedWalRecords() {
    return replayedWalRecords.get();
  }

  /** Returns the latest startup failure message, if any. */
  public String failureMessage() {
    return failureMessage;
  }

  /** Returns whether startup recovery has completed successfully. */
  public boolean isReady() {
    return phase.get() == Phase.READY;
  }

  /** Marks recovery as in progress and clears previous recovery state. */
  public void markRecovering() {
    replayedWalRecords.set(0);
    failureMessage = "";
    phase.set(Phase.RECOVERING);
  }

  /** Marks recovery as successful with its replayed-record count. */
  public void markReady(int replayedRecords) {
    replayedWalRecords.set(replayedRecords);
    failureMessage = "";
    phase.set(Phase.READY);
  }

  /** Marks recovery as failed and records a diagnostic message. */
  public void markFailed(Throwable failure) {
    replayedWalRecords.set(0);
    failureMessage = resolveFailureMessage(failure);
    phase.set(Phase.FAILED);
  }

  private String resolveFailureMessage(Throwable failure) {
    final String message = failure.getMessage();
    if (message != null && !message.isBlank()) {
      return message;
    }
    return failure.getClass().getSimpleName();
  }
}
