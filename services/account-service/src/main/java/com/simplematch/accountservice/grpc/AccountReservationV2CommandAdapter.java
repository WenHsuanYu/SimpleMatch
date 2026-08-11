package com.simplematch.accountservice.grpc;

import com.simplematch.accountservice.reservation.ReservationRequestIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.contracts.account.v2.ReservationAction;
import com.simplematch.contracts.account.v2.ReservationCommand;
import java.math.BigDecimal;
import java.util.UUID;

/** Converts and validates the typed v2 reservation command at the RPC boundary. */
final class AccountReservationV2CommandAdapter {
  private static final int TWD_SCALE = 4;

  private AccountReservationV2CommandAdapter() {}

  static ReserveOperation toReserveOperation(ReservationCommand request) {
    validateCommand(request);
    return new ReserveOperation(toRequestIdentity(request), toReservationTerms(request));
  }

  private static void validateCommand(ReservationCommand request) {
    if (request == null) {
      throw new IllegalArgumentException("reservation command is required");
    }
    if (!"v2".equals(request.getMetadata().getSchemaVersion())) {
      throw new IllegalArgumentException("schema_version must be v2");
    }
    if (request.getAction() != ReservationAction.RESERVATION_ACTION_RESERVE) {
      throw new IllegalArgumentException("reservation action must be RESERVE");
    }
  }

  private static ReservationRequestIdentity toRequestIdentity(ReservationCommand request) {
    final String commandId = uuid(request.getCommandId(), "command_id");
    final String orderId = uuid(request.getOrderId(), "order_id");
    final String accountId = uuid(request.getAccountId(), "account_id");
    final String reservationId = uuid(request.getReservationId(), "reservation_id");
    if (!reservationId.equals(orderId)) {
      throw new IllegalArgumentException(
          "reservation_id must equal order_id for Account Authority");
    }
    return new ReservationRequestIdentity(
        new ReservationRequestIdentity.RequestId(commandId),
        new ReservationRequestIdentity.OrderId(orderId),
        new ReservationRequestIdentity.AccountId(accountId));
  }

  private static ReservationTerms toReservationTerms(ReservationCommand request) {
    validateTerms(request);
    final com.simplematch.contracts.common.v1.Side side = toInternalSide(request);
    final long quantity = request.getQuantity().getShares();
    final long priceUnits = request.getLimitPrice().getUnits();
    return new ReservationTerms(
        new ReservationTerms.InstrumentSymbol(request.getInstrument().getSymbol()),
        new ReservationTerms.VenueMic(request.getInstrument().getVenueMic()),
        side,
        new ReservationTerms.ReservationQuantity(BigDecimal.valueOf(quantity)),
        priceUnits == 0
            ? ReservationTerms.LimitPrice.absent()
            : new ReservationTerms.LimitPrice(BigDecimal.valueOf(priceUnits, TWD_SCALE)));
  }

  private static void validateTerms(ReservationCommand request) {
    if (request.getInstrument().getSymbol().isBlank()) {
      throw new IllegalArgumentException("instrument.symbol must not be blank");
    }
    if (request.getInstrument().getVenueMic().isBlank()) {
      throw new IllegalArgumentException("instrument.venue_mic must not be blank");
    }
    final long quantity = request.getQuantity().getShares();
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity.shares must be positive");
    }
    final long priceUnits = request.getLimitPrice().getUnits();
    if (priceUnits < 0) {
      throw new IllegalArgumentException("limit_price.units must not be negative");
    }
    validateNotional(request, priceUnits, quantity);
  }

  private static void validateNotional(
      ReservationCommand request, long priceUnits, long quantity) {
    if (request.getNotional().getUnits() != expectedNotionalUnits(priceUnits, quantity)) {
      throw new IllegalArgumentException(
          "notional.units must equal quantity.shares * limit_price.units");
    }
  }

  private static com.simplematch.contracts.common.v1.Side toInternalSide(
      ReservationCommand request) {
    return switch (request.getSide()) {
      case SIDE_BUY -> com.simplematch.contracts.common.v1.Side.SIDE_BUY;
      case SIDE_SELL -> com.simplematch.contracts.common.v1.Side.SIDE_SELL;
      default -> throw new IllegalArgumentException("side must be specified");
    };
  }

  private static String uuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    try {
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(fieldName + " must be a UUID", invalid);
    }
  }

  private static long expectedNotionalUnits(long priceUnits, long quantity) {
    try {
      return Math.multiplyExact(priceUnits, quantity);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("notional exceeds fixed-point range", overflow);
    }
  }
}
