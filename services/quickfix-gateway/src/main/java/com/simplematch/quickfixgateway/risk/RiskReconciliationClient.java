package com.simplematch.quickfixgateway.risk;

/** Looks up Risk's durable admission state for a command whose RPC result is uncertain. */
public interface RiskReconciliationClient {
  /** Returns Risk's authoritative admission snapshot for the supplied command identity. */
  RiskReconciliationResult lookup(String commandId);
}
