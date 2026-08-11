package com.simplematch.quickfixgateway.operations;

/** Gateway-owned boundary that retains an operational command and its resulting admission state. */
public interface GatewayOperationAuditStore {
  /** Persists one completed operator or automatic operation audit record. */
  void append(GatewayOperationAudit audit);
}
