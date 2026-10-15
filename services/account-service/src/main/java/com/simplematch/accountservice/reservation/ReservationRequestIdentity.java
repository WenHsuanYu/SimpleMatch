package com.simplematch.accountservice.reservation;

import java.util.Objects;
import java.util.UUID;

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
  public record RequestId(UUID value) {
    /** Parses a UUID identifier at a text boundary. */
    public RequestId(String value) {
      this(parseUuid(value, "request_id"));
    }

    /** Requires a concrete UUID identifier. */
    public RequestId {
      Objects.requireNonNull(value, "value");
    }

    /** Returns the canonical representation used at transport boundaries. */
    public String wireValue() {
      return value.toString();
    }
  }

  /** Order identity associated with the reservation. */
  public record OrderId(UUID value) {
    /** Parses a UUID identifier at a text boundary. */
    public OrderId(String value) {
      this(parseUuid(value, "order_id"));
    }

    /** Requires a concrete UUID identifier. */
    public OrderId {
      Objects.requireNonNull(value, "value");
    }

    /** Returns the canonical representation used at transport boundaries. */
    public String wireValue() {
      return value.toString();
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

  private static UUID parseUuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be a UUID");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(fieldName + " must be a UUID", invalid);
    }
  }
}
