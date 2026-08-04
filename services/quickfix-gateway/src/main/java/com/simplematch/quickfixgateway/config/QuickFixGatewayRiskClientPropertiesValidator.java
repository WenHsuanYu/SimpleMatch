package com.simplematch.quickfixgateway.config;

/** Validates bounded QuickFIX gateway risk-client settings during startup. */
public final class QuickFixGatewayRiskClientPropertiesValidator {
  private QuickFixGatewayRiskClientPropertiesValidator() {}

  /** Validates service-owned risk-client property ranges. */
  public static void validate(QuickFixGatewayRiskClientProperties properties) {
    if (properties.deadlineMillis() <= 0
        || properties.retry().maxAttempts() <= 0
        || properties.retry().backoffMillis() < 0
        || properties.breaker().consecutiveFailures() <= 0
        || properties.breaker().openDurationMillis() <= 0) {
      throw new IllegalStateException(
          "QuickFIX gateway risk-client settings must use bounded positive values.");
    }
  }
}
