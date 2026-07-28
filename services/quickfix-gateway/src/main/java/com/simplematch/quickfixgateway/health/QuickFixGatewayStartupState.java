package com.simplematch.quickfixgateway.health;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks readiness-relevant startup state for the quickfix-gateway owner process.
 */
public final class QuickFixGatewayStartupState {
    /**
     * Startup phases surfaced to readiness checks and operator diagnostics.
     */
    public enum Phase {
        STARTING,
        RECOVERING,
        READY,
        FAILED
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.STARTING);
    private final AtomicInteger replayedWalRecords = new AtomicInteger();

    private volatile String failureMessage = "";

    public Phase phase() {
        return phase.get();
    }

    public int replayedWalRecords() {
        return replayedWalRecords.get();
    }

    public String failureMessage() {
        return failureMessage;
    }

    public boolean isReady() {
        return phase.get() == Phase.READY;
    }

    public void markRecovering() {
        replayedWalRecords.set(0);
        failureMessage = "";
        phase.set(Phase.RECOVERING);
    }

    public void markReady(int replayedRecords) {
        replayedWalRecords.set(replayedRecords);
        failureMessage = "";
        phase.set(Phase.READY);
    }

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