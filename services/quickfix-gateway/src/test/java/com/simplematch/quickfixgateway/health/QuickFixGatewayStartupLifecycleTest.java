package com.simplematch.quickfixgateway.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickFixGatewayStartupLifecycleTest {
    @DisplayName("startup recovery marks the gateway ready after recovery completes")
    @Test
    void startupRecoveryMarksGatewayReadyAfterRecoveryCompletes() {
        final QuickFixGatewayStartupState startupState = new QuickFixGatewayStartupState();
        final AtomicBoolean recoveryInvoked = new AtomicBoolean(false);
        final QuickFixGatewayStartupLifecycle lifecycle = new QuickFixGatewayStartupLifecycle(
                "quickfix-gateway-0",
                () -> {
                    recoveryInvoked.set(true);
                    return 2;
                },
                startupState);

        lifecycle.start();

        assertThat(recoveryInvoked).isTrue();
        assertThat(startupState.phase()).isEqualTo(QuickFixGatewayStartupState.Phase.READY);
        assertThat(startupState.replayedWalRecords()).isEqualTo(2);
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @DisplayName("startup recovery marks the gateway failed when recovery throws")
    @Test
    void startupRecoveryMarksGatewayFailedWhenRecoveryThrows() {
        final QuickFixGatewayStartupState startupState = new QuickFixGatewayStartupState();
        final QuickFixGatewayStartupLifecycle lifecycle = new QuickFixGatewayStartupLifecycle(
                "quickfix-gateway-0",
                () -> {
                    throw new IllegalStateException("boom");
                },
                startupState);

        assertThatThrownBy(lifecycle::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup recovery failed");

        assertThat(startupState.phase()).isEqualTo(QuickFixGatewayStartupState.Phase.FAILED);
        assertThat(startupState.failureMessage()).contains("boom");
    }
}