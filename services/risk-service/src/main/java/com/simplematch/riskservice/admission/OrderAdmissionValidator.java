package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Validates v2 order fields without transport, database, or account side effects. */
@Component
public final class OrderAdmissionValidator {
  /** Converts a valid v2 command into the journal's transport-independent carrier. */
  @SuppressWarnings("PMD.CyclomaticComplexity") // Admission validation is deliberately one visible policy boundary.
  public AdmissionCommand validate(NewOrderCommand command) {
    if (command == null) {
      throw new AdmissionValidationException("INVALID_COMMAND", "new order command is required");
    }
    final UUID commandId = uuid(command.getCommandId(), "command_id");
    final UUID orderId = uuid(command.getOrderId(), "order_id");
    final UUID accountId = uuid(command.getAccountId(), "account_id");
    if (command.getInstrument().getSymbol().isBlank()) {
      throw new AdmissionValidationException("INVALID_INSTRUMENT", "symbol is required");
    }
    final String venue = command.getInstrument().getVenueMic();
    if (!"XTAI".equals(venue) && !"ROCO".equals(venue)) {
      throw new AdmissionValidationException("INVALID_INSTRUMENT", "venue_mic must be XTAI or ROCO");
    }
    if (command.getSide() == Side.SIDE_UNSPECIFIED || command.getQuantity().getShares() <= 0) {
      throw new AdmissionValidationException("INVALID_COMMAND", "side and positive quantity are required");
    }
    if (command.getOrderType() == OrderType.ORDER_TYPE_UNSPECIFIED
        || command.getTif() == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
        || command.getCurrency() != Currency.CURRENCY_TWD) {
      throw new AdmissionValidationException("INVALID_COMMAND", "order type, time-in-force, and TWD currency are required");
    }
    if (command.getSessionState() != SessionState.SESSION_STATE_CONTINUOUS) {
      throw new AdmissionValidationException("UNSUPPORTED_SESSION", "new-order admission requires CONTINUOUS session");
    }
    final Long limitPrice = command.getOrderType() == OrderType.ORDER_TYPE_LIMIT
        ? positive(command.getLimitPrice().getUnits(), "limit_price") : null;
    if (command.getTradingDay().getIsoDate().isBlank()) {
      throw new AdmissionValidationException("INVALID_COMMAND", "trading_day is required");
    }
    final LocalDate tradingDay;
    try {
      tradingDay = LocalDate.parse(command.getTradingDay().getIsoDate());
    } catch (DateTimeParseException exception) {
      throw new AdmissionValidationException("INVALID_COMMAND", "trading_day must be ISO-8601");
    }
    if (command.getSenderCompId().isBlank() || command.getTargetCompId().isBlank() || command.getClOrdId().isBlank()) {
      throw new AdmissionValidationException("INVALID_COMMAND", "sender, target, and cl_ord_id are required");
    }
    final UUID routingSnapshotId = command.getRoutingSnapshotId().isBlank()
        ? null : uuid(command.getRoutingSnapshotId(), "routing_snapshot_id");
    return new AdmissionCommand(commandId, orderId, accountId, command.getInstrument().getSymbol(), venue,
        command.getSide().name(), command.getQuantity().getShares(), limitPrice, command.getOrderType().name(),
        command.getTif().name(), tradingDay, command.getSenderCompId(), command.getTargetCompId(), command.getClOrdId(),
        routingSnapshotId);
  }

  /** Converts a valid cancel command into the same durable identity carrier. */
  @SuppressWarnings("PMD.CyclomaticComplexity") // Cancel validation is an indivisible admission policy.
  public AdmissionCommand validateCancel(CancelOrderCommand command) {
    if (command == null) {
      throw new AdmissionValidationException("INVALID_COMMAND", "cancel command is required");
    }
    final UUID commandId = uuid(command.getCommandId(), "command_id");
    final UUID orderId = uuid(command.getOrderId(), "order_id");
    final UUID accountId = uuid(command.getAccountId(), "account_id");
    if (command.getInstrument().getSymbol().isBlank()
        || (!"XTAI".equals(command.getInstrument().getVenueMic())
            && !"ROCO".equals(command.getInstrument().getVenueMic()))) {
      throw new AdmissionValidationException("INVALID_INSTRUMENT", "symbol and supported venue are required");
    }
    if (command.getSide() == Side.SIDE_UNSPECIFIED || command.getOrigClOrdId().isBlank()
        || command.getSenderCompId().isBlank() || command.getTargetCompId().isBlank()
        || command.getClOrdId().isBlank()) {
      throw new AdmissionValidationException("INVALID_COMMAND", "cancel identity and side are required");
    }
    if (command.getSessionState() != SessionState.SESSION_STATE_CONTINUOUS) {
      throw new AdmissionValidationException("UNSUPPORTED_SESSION", "cancel admission requires CONTINUOUS session");
    }
    final LocalDate tradingDay;
    try {
      tradingDay = LocalDate.parse(command.getTradingDay().getIsoDate());
    } catch (DateTimeParseException exception) {
      throw new AdmissionValidationException("INVALID_COMMAND", "trading_day must be ISO-8601");
    }
    return new AdmissionCommand(commandId, orderId, accountId, command.getInstrument().getSymbol(),
        command.getInstrument().getVenueMic(), command.getSide().name(), 1, null, "CANCEL", "CANCEL",
        tradingDay, command.getSenderCompId(), command.getTargetCompId(), command.getClOrdId(), null);
  }

  private UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new AdmissionValidationException("INVALID_COMMAND", field + " is required");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new AdmissionValidationException("INVALID_COMMAND", field + " must be a UUID");
    }
  }

  private long positive(long value, String field) {
    if (value <= 0) {
      throw new AdmissionValidationException("INVALID_COMMAND", field + " must be positive");
    }
    return value;
  }
}
