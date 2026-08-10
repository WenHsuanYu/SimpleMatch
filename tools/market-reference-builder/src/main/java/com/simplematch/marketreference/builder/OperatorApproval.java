package com.simplematch.marketreference.builder;

/**
 * Explicit human approval evidence required before a final artifact can be retained for delivery.
 */
public record OperatorApproval(String approvedBy, long approvedAtUnixMs) {
  /** Requires a named operator and a real approval timestamp. */
  public OperatorApproval {
    if (approvedBy == null || approvedBy.isBlank()) {
      throw new MarketReferenceBuildException("operator approval identity is required");
    }
    approvedBy = approvedBy.trim();
    if (approvedAtUnixMs <= 0) {
      throw new MarketReferenceBuildException("operator approval time must be positive");
    }
  }
}
