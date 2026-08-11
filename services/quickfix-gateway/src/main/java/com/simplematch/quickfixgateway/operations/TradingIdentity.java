package com.simplematch.quickfixgateway.operations;

/**
 * Immutable identity that every Phase 1 critical participant must share for a trading session.
 *
 * <p>It intentionally contains only domain strings and schema versions, never an infrastructure
 * client object or deployment-specific resource type.
 */
public record TradingIdentity(
    String tradingSessionId,
    String artifactId,
    String artifactContentSha256,
    int commandSchemaVersion,
    int eventSchemaVersion,
    String matchingAlgorithmVersion,
    String matchingImageIdentity) {
  /** Validates the identity fields reported by an infrastructure adapter. */
  public TradingIdentity {
    tradingSessionId =
        OperationalStatusValidation.requiredText(tradingSessionId, "tradingSessionId");
    artifactId = OperationalStatusValidation.requiredText(artifactId, "artifactId");
    artifactContentSha256 =
        OperationalStatusValidation.requiredText(artifactContentSha256, "artifactContentSha256");
    commandSchemaVersion =
        OperationalStatusValidation.positive(commandSchemaVersion, "commandSchemaVersion");
    eventSchemaVersion =
        OperationalStatusValidation.positive(eventSchemaVersion, "eventSchemaVersion");
    matchingAlgorithmVersion =
        OperationalStatusValidation.requiredText(
            matchingAlgorithmVersion, "matchingAlgorithmVersion");
    matchingImageIdentity =
        OperationalStatusValidation.requiredText(matchingImageIdentity, "matchingImageIdentity");
  }
}
