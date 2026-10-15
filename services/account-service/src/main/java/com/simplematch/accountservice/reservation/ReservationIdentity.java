package com.simplematch.accountservice.reservation;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of one account reservation across synchronous requests and lifecycle events.
 *
 * <p>Each identifier has a distinct Java type. A caller therefore cannot accidentally exchange a
 * request identifier, reservation identifier, and order identifier while constructing the domain
 * value.
 *
 * @param requestId the idempotent request identifier
 * @param reservationId the account-owned reservation identifier
 * @param orderId the order that owns the reservation
 */
public record ReservationIdentity(
    RequestId requestId, ReservationId reservationId, OrderId orderId) {
  /** Requires every typed identity component. */
  public ReservationIdentity {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(orderId, "orderId");
  }

  /** Idempotent operation identity used by the synchronous reservation boundary. */
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

  /** Account-owned identity of one reservation. */
  public record ReservationId(UUID value) {
    /** Parses a UUID identifier at a text boundary. */
    public ReservationId(String value) {
      this(parseUuid(value, "reservation_id"));
    }

    /** Requires a concrete UUID identifier. */
    public ReservationId {
      Objects.requireNonNull(value, "value");
    }

    /** Returns the canonical representation used at transport boundaries. */
    public String wireValue() {
      return value.toString();
    }
  }

  /** Identity of the order that owns the reservation. */
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
