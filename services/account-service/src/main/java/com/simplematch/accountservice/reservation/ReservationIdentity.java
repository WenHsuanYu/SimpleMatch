package com.simplematch.accountservice.reservation;

import java.util.Objects;

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
  public record RequestId(String value) {
    /** Requires a nonblank request identifier. */
    public RequestId {
      value = requireNonBlank(value, "request_id");
    }
  }

  /** Account-owned identity of one reservation. */
  public record ReservationId(String value) {
    /** Requires a nonblank reservation identifier. */
    public ReservationId {
      value = requireNonBlank(value, "reservation_id");
    }
  }

  /** Identity of the order that owns the reservation. */
  public record OrderId(String value) {
    /** Requires a nonblank order identifier. */
    public OrderId {
      value = requireNonBlank(value, "order_id");
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
