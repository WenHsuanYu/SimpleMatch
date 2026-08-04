package com.simplematch.riskservice.outbox;

/**
 * Supplies an explicit partition for the source-compatible v1 outbox seam.
 *
 * <p>This interface is not a production routing authority. Production v2 admission resolves from
 * the local Market Reference projection through {@code AdmissionRoutingPolicyResolver}; no
 * default, hash, or JSON implementation is allowed here.
 */
@FunctionalInterface
public interface RoutingPartitionResolver {
  /** Resolves the partition for the supplied persisted message key. */
  int resolve(String messageKey);
}
