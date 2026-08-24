package com.simplematch.quickfixgateway.fix;

import java.util.Objects;
import java.util.UUID;
import quickfix.SessionID;

/**
 * FIX session and immutable order facts that own one final Matching Event report.
 *
 * @param orderId canonical Risk/Matching order identity from the final Matching Event
 * @param sessionId owning QuickFIX session
 * @param order durable FIX-facing order facts, including the client-visible OrderID
 */
public record FinalFixDeliveryRecipient(UUID orderId, SessionID sessionId, FixOrderSnapshot order) {
  /** Requires both canonical recipient identity and durable FIX-facing order facts. */
  public FinalFixDeliveryRecipient {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(order, "order");
  }
}
