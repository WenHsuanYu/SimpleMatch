package com.simplematch.quickfixgateway.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class QuickFixGatewayReadinessHealthIndicatorTest {
    @DisplayName("readiness stays out of service until startup recovery completes")
    @Test
    void readinessStaysOutOfServiceUntilStartupRecoveryCompletes() {
        final QuickFixGatewayStartupState startupState = new QuickFixGatewayStartupState();
        final QuickFixGatewayReadinessHealthIndicator healthIndicator = new QuickFixGatewayReadinessHealthIndicator(
                startupState,
                false,
                () -> false);

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(healthIndicator.health().getDetails()).containsEntry("phase", "STARTING");
    }

    @DisplayName("readiness requires the QuickFIX acceptor when the acceptor is enabled")
    @Test
    void readinessRequiresQuickFixAcceptorWhenEnabled() {
        final QuickFixGatewayStartupState startupState = new QuickFixGatewayStartupState();
        startupState.markRecovering();
        startupState.markReady(0);
        final QuickFixGatewayReadinessHealthIndicator healthIndicator = new QuickFixGatewayReadinessHealthIndicator(
                startupState,
                true,
                () -> false);

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(healthIndicator.health().getDetails()).containsEntry("acceptorRunning", false);
    }

    @DisplayName("readiness becomes up once recovery is complete and the acceptor is running")
    @Test
    void readinessBecomesUpWhenRecoveryIsCompleteAndAcceptorIsRunning() {
        final QuickFixGatewayStartupState startupState = new QuickFixGatewayStartupState();
        startupState.markRecovering();
        startupState.markReady(3);
        final QuickFixGatewayReadinessHealthIndicator healthIndicator = new QuickFixGatewayReadinessHealthIndicator(
                startupState,
                true,
                () -> true);

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(healthIndicator.health().getDetails()).containsEntry("phase", "READY");
        assertThat(healthIndicator.health().getDetails()).containsEntry("replayedWalRecords", 3);
    }
}