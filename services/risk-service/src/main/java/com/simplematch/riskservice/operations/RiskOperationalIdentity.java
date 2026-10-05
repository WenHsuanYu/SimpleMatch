package com.simplematch.riskservice.operations;

import java.util.regex.Pattern;

/** Risk-owned verified daily identity exposed to operational coordination. */
public record RiskOperationalIdentity(
    String tradingSessionId,
    String artifactId,
    String artifactContentSha256,
    int commandSchemaVersion,
    int eventSchemaVersion,
    String matchingAlgorithmVersion,
    String matchingImageIdentity) {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  /** Rejects any identity that cannot be safely compared across live components. */
  public RiskOperationalIdentity {
    tradingSessionId = required(tradingSessionId, "tradingSessionId");
    artifactId = required(artifactId, "artifactId");
    artifactContentSha256 = requiredSha256(artifactContentSha256, "artifactContentSha256");
    if (commandSchemaVersion <= 0 || eventSchemaVersion <= 0) {
      throw new IllegalArgumentException("schema versions must be positive");
    }
    matchingAlgorithmVersion = required(matchingAlgorithmVersion, "matchingAlgorithmVersion");
    matchingImageIdentity = required(matchingImageIdentity, "matchingImageIdentity");
    if (!matchingImageIdentity.startsWith("sha256:")
        || !SHA256.matcher(matchingImageIdentity.substring(7)).matches()) {
      throw new IllegalArgumentException("matchingImageIdentity must be a canonical digest");
    }
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requiredSha256(String value, String name) {
    final String requiredValue = required(value, name);
    if (!SHA256.matcher(requiredValue).matches()) {
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    }
    return requiredValue;
  }
}
