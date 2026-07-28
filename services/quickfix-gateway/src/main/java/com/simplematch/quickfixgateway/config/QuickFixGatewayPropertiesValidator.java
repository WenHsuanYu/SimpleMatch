package com.simplematch.quickfixgateway.config;

/**
 * Validates bounded QuickFIX gateway resilience settings during application startup.
 */
public final class QuickFixGatewayPropertiesValidator {
    private QuickFixGatewayPropertiesValidator() {
    }

    /**
     * Validates service-owned gateway property ranges.
     */
    public static void validate(QuickFixGatewayProperties properties) {
        final QuickFixGatewayProperties.RiskClientProperties riskClient = properties.riskClient();
        if (riskClient.deadlineMillis() <= 0
                || riskClient.retry().maxAttempts() <= 0
                || riskClient.retry().backoffMillis() < 0
                || riskClient.breaker().consecutiveFailures() <= 0
                || riskClient.breaker().openDurationMillis() <= 0) {
            throw new IllegalStateException("QuickFIX gateway risk-client settings must use bounded positive values.");
        }
    }
}
