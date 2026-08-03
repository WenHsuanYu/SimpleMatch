package com.simplematch.accountservice.reservation;

import java.util.Objects;

/**
 * Identity supplied when account authority is first reserved for an order.
 *
 * <p>The request, order, and account identifiers are distinct value types even though all three
 * arrive as strings on the wire. A caller therefore cannot accidentally exchange them while
 * constructing a reservation request.
 *
 * @param requestId the idempotent request identifier
 * @param orderId the order that owns the requested authority
 * @param accountId the account from which authority is requested
 */
public record ReservationRequestIdentity(
    RequestId requestId, OrderId orderId, AccountId accountId) {
  /** Requires all three typed identifiers. */
  public ReservationRequestIdentity {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(accountId, "accountId");
  }

  /** Idempotency identity of one reserve operation. */
  public record RequestId(String value) {
    /** Requires a nonblank request identifier. */
    public RequestId {
      value = requireNonBlank(value, "request_id");
    }
  }

  /** Order identity associated with the reservation. */
  public record OrderId(String value) {
    /** Requires a nonblank order identifier. */
    public OrderId {
      value = requireNonBlank(value, "order_id");
    }
  }

  /** Account identity whose authority is reserved. */
  public record AccountId(String value) {
    /** Requires a nonblank account identifier. */
    public AccountId {
      value = requireNonBlank(value, "account_id");
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
