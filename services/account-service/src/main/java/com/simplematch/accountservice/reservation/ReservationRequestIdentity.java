package com.simplematch.accountservice.reservation;

import java.util.Objects;

/**
 * Identity supplied when account authority is first reserved for an order.
 *
 * <p>The request, order, and account identifiers are distinct value types. Account identity wraps
 * the UUID-backed value owned by the Account domain while preserving this command model's semantic
 * component type.
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
  public record AccountId(com.simplematch.accountservice.authority.AccountId canonical) {
    /** Parses the wire representation into the canonical Account-domain identity. */
    public AccountId(String value) {
      this(com.simplematch.accountservice.authority.AccountId.parse(value));
    }

    /** Requires the canonical account identity. */
    public AccountId {
      Objects.requireNonNull(canonical, "canonical");
    }

    /** Returns the canonical wire representation. */
    public String value() {
      return canonical.wireValue();
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
