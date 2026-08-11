package com.simplematch.quickfixgateway.fix;

import java.util.Objects;
import java.util.UUID;
import quickfix.SessionID;

/**
 * FIX session and immutable order facts that own one final Matching Event report.
 *
 * @param orderId recipient order identity from the final Matching Event
 * @param sessionId owning QuickFIX session
 * @param order durable FIX-facing order facts
 */
public record FinalFixDeliveryRecipient(UUID orderId, SessionID sessionId, FixOrderSnapshot order) {
  /** Requires the persisted recipient to agree with the gateway's durable order facts. */
  public FinalFixDeliveryRecipient {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(order, "order");
    if (!orderId.toString().equals(order.orderId().value())) {
      throw new IllegalArgumentException("recipient order must match the FIX order snapshot");
    }
  }
}
