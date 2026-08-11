package com.simplematch.quickfixgateway.fix;

/**
 * Stable identities for one client-facing final Matching Event delivery.
 *
 * @param deliveryId SHA-256 delivery identity, stored as 32 bytes in PostgreSQL
 * @param eventId SHA-256 final Matching Event identity
 * @param deliveryIndex deterministic position within the event
 */
public record FinalFixDeliveryIdentity(String deliveryId, String eventId, int deliveryIndex) {
  /** Requires canonical binary-backed identities and a bounded event-local index. */
  public FinalFixDeliveryIdentity {
    requireHex(deliveryId, "deliveryId");
    requireHex(eventId, "eventId");
    if (deliveryIndex < 0 || deliveryIndex > 1) {
      throw new IllegalArgumentException("deliveryIndex must be between 0 and 1");
    }
  }

  private static void requireHex(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
    }
  }
}
