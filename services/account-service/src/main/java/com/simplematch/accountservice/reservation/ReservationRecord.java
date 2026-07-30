package com.simplematch.accountservice.reservation;

import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Persisted reservation snapshot stored in {@code account_reservations}.
 *
 * @param reservationId the persisted reservation identifier; currently aligned with {@code
 *     order_id}
 * @param requestId the synchronous request identifier used for idempotent replay
 * @param orderId the order that owns the reservation
 * @param accountId the account that owns the reservation
 * @param symbol the instrument symbol the reservation applies to
 * @param side the side associated with the reservation request
 * @param quantity the requested quantity to reserve
 * @param limitPrice the optional limit price carried with the request
 * @param reservedNotional the notional amount reserved for this request
 * @param status the persisted reservation status
 * @param reasonCode the machine-readable decision code
 * @param reasonText the human-readable decision text
 * @param createdAtUnixMs the first persisted timestamp for the reservation
 * @param updatedAtUnixMs the last updated timestamp for the reservation
 */
public record ReservationRecord(
    String reservationId,
    String requestId,
    String orderId,
    String accountId,
    String symbol,
    Side side,
    BigDecimal quantity,
    BigDecimal limitPrice,
    BigDecimal reservedNotional,
    ReservationStatus status,
    String reasonCode,
    String reasonText,
    long createdAtUnixMs,
    long updatedAtUnixMs) {
  public ReservationRecord {
    reservationId = requireNonBlank(reservationId, "reservation_id");
    requestId = requireNonBlank(requestId, "request_id");
    orderId = requireNonBlank(orderId, "order_id");
    accountId = requireNonBlank(accountId, "account_id");
    symbol = requireNonBlank(symbol, "symbol");
    side = requireNonNullValue(side, "side");
    quantity = requireNonNull(quantity, "quantity");
    reservedNotional = requireNonNull(reservedNotional, "reserved_notional");
    status = requireNonNullValue(status, "status");
    reasonCode = Objects.requireNonNullElse(reasonCode, "");
    reasonText = Objects.requireNonNullElse(reasonText, "");
  }

  /**
   * Creates the first persisted accepted reservation snapshot for a reserve operation.
   *
   * @param operation the validated reservation operation
   * @param createdAtUnixMs the timestamp for the created and updated columns
   * @return the accepted reservation snapshot
   */
  public static ReservationRecord accepted(ReserveOperation operation, long createdAtUnixMs) {
    final BigDecimal reservedNotional =
        operation.limitPrice() == null
            ? BigDecimal.ZERO
            : operation.limitPrice().multiply(operation.quantity());
    return new ReservationRecord(
        operation.orderId(),
        operation.requestId(),
        operation.orderId(),
        operation.accountId(),
        operation.symbol(),
        operation.side(),
        operation.quantity(),
        operation.limitPrice(),
        reservedNotional,
        ReservationStatus.RESERVATION_STATUS_ACCEPTED,
        "",
        "",
        createdAtUnixMs,
        createdAtUnixMs);
  }

  /** Creates a stable rejected reservation result without changing account balances. */
  public static ReservationRecord rejected(
      ReserveOperation operation, String reasonCode, String reasonText, long createdAtUnixMs) {
    return new ReservationRecord(
        operation.orderId(),
        operation.requestId(),
        operation.orderId(),
        operation.accountId(),
        operation.symbol(),
        operation.side(),
        operation.quantity(),
        operation.limitPrice(),
        BigDecimal.ZERO,
        ReservationStatus.RESERVATION_STATUS_REJECTED,
        reasonCode,
        reasonText,
        createdAtUnixMs,
        createdAtUnixMs);
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private static BigDecimal requireNonNull(BigDecimal value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }

  private static <T> T requireNonNullValue(T value, String fieldName) {
    return Objects.requireNonNull(value, fieldName + " must not be null");
  }
}
