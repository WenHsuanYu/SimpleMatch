package com.simplematch.quickfixgateway.health;

/**
 * Executes owner-local startup recovery before the gateway accepts FIX traffic.
 */
@FunctionalInterface
public interface QuickFixGatewayStartupRecovery {
    /**
     * Runs the recovery step and returns the number of WAL records replayed.
     *
     * @return replayed WAL record count.
     */
    int recover();
}