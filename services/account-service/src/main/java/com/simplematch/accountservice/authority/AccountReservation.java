package com.simplematch.accountservice.authority;

import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;

/** Immutable authoritative reservation state used by lifecycle operations. */
public record AccountReservation(
    String reservationId,
    String requestId,
    String orderId,
    String accountId,
    String symbol,
    Side side,
    BigDecimal quantity,
    BigDecimal remainingQuantity,
    BigDecimal filledQuantity,
    BigDecimal limitPrice,
    BigDecimal reservedNotional,
    ReservationStatus status,
    String reasonCode,
    String reasonText,
    long version,
    long createdAtUnixMs,
    long updatedAtUnixMs) {
  /** Validates lifecycle quantities and status fields. */
  public AccountReservation {
    text(reservationId, "reservation_id");
    text(requestId, "request_id");
    text(orderId, "order_id");
    text(accountId, "account_id");
    text(symbol, "symbol");
    if (side == null || side == Side.SIDE_UNSPECIFIED) {
      throw new IllegalArgumentException("side must be specified");
    }
    if (quantity == null
        || quantity.signum() <= 0
        || remainingQuantity == null
        || remainingQuantity.signum() < 0
        || filledQuantity == null
        || filledQuantity.signum() < 0
        || (status != ReservationStatus.RESERVATION_STATUS_RELEASED
            && filledQuantity.add(remainingQuantity).compareTo(quantity) != 0)) {
      throw new IllegalArgumentException("reservation quantities are invalid");
    }
    if (limitPrice != null && limitPrice.signum() <= 0) {
      throw new IllegalArgumentException("limit_price must be positive when provided");
    }
    if (reservedNotional == null || reservedNotional.signum() < 0) {
      throw new IllegalArgumentException("reserved_notional must be non-negative");
    }
    if (status == null || status == ReservationStatus.RESERVATION_STATUS_UNSPECIFIED) {
      throw new IllegalArgumentException("status must be specified");
    }
    if (version < 0 || createdAtUnixMs < 0 || updatedAtUnixMs < createdAtUnixMs) {
      throw new IllegalArgumentException("reservation version and timestamps are invalid");
    }
  }

  /** Returns an accepted reservation with all requested quantity outstanding. */
  public static AccountReservation accepted(
      String reservationId,
      ReserveOperationSnapshot operation,
      BigDecimal reservedNotional,
      long now) {
    return new AccountReservation(
        reservationId,
        operation.requestId(),
        operation.orderId(),
        operation.accountId(),
        operation.symbol(),
        operation.side(),
        operation.quantity(),
        operation.quantity(),
        BigDecimal.ZERO,
        operation.limitPrice(),
        reservedNotional,
        ReservationStatus.RESERVATION_STATUS_ACCEPTED,
        "",
        "",
        0,
        now,
        now);
  }

  /** Returns a stable rejected reservation without changing account balances. */
  public static AccountReservation rejected(
      String reservationId,
      ReserveOperationSnapshot operation,
      String reasonCode,
      String reasonText,
      long now) {
    return new AccountReservation(
        reservationId,
        operation.requestId(),
        operation.orderId(),
        operation.accountId(),
        operation.symbol(),
        operation.side(),
        operation.quantity(),
        operation.quantity(),
        BigDecimal.ZERO,
        operation.limitPrice(),
        BigDecimal.ZERO,
        ReservationStatus.RESERVATION_STATUS_REJECTED,
        reasonCode,
        reasonText,
        0,
        now,
        now);
  }

  /** Compact validated operation view used by the authority without coupling to gRPC. */
  public record ReserveOperationSnapshot(
      String requestId,
      String orderId,
      String accountId,
      String symbol,
      Side side,
      BigDecimal quantity,
      BigDecimal limitPrice) {}

  private static void text(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
