package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Validates v2 order fields without transport, database, or account side effects.
 */
@Component
public final class OrderAdmissionValidator {
    /**
     * Converts a valid v2 command into the journal's transport-independent carrier.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity") // Admission validation is deliberately one visible policy boundary.
    public AdmissionCommand validate(NewOrderCommand command) {
        if (command == null) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("new order command is required"));
        }
        final UUID commandId = uuid(command.getCommandId(), "command_id");
        final UUID orderId = uuid(command.getOrderId(), "order_id");
        final UUID accountId = uuid(command.getAccountId(), "account_id");
        if (command.getInstrument().getSymbol().isBlank()) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidInstrument("symbol is required"));
        }
        final String venue = command.getInstrument().getVenueMic();
        if (!"XTAI".equals(venue) && !"ROCO".equals(venue)) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidInstrument("venue_mic must be XTAI or ROCO"));
        }
        if (command.getSide() == Side.SIDE_UNSPECIFIED || command.getQuantity().getShares() <= 0) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("side and positive quantity are required"));
        }
        if (command.getOrderType() == OrderType.ORDER_TYPE_UNSPECIFIED
                || command.getTif() == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
                || command.getCurrency() != Currency.CURRENCY_TWD) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand(
                            "order type, time-in-force, and TWD currency are required"));
        }
        if (command.getSessionState() != SessionState.SESSION_STATE_CONTINUOUS) {
            throw new AdmissionValidationException(
                    AdmissionFailure.unsupportedSession(
                            "new-order admission requires CONTINUOUS session"));
        }
        final Long limitPrice = command.getOrderType() == OrderType.ORDER_TYPE_LIMIT
                ? positive(command.getLimitPrice().getUnits(), "limit_price") : null;
        if (command.getTradingDay().getIsoDate().isBlank()) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("trading_day is required"));
        }
        final LocalDate tradingDay;
        try {
            tradingDay = LocalDate.parse(command.getTradingDay().getIsoDate());
        } catch (DateTimeParseException exception) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("trading_day must be ISO-8601"));
        }
        if (command.getSenderCompId().isBlank()
                || command.getTargetCompId().isBlank()
                || command.getClOrdId().isBlank()) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("sender, target, and cl_ord_id are required"));
        }
        final UUID routingSnapshotId = command.getRoutingSnapshotId().isBlank()
                ? null : uuid(command.getRoutingSnapshotId(), "routing_snapshot_id");
        return new AdmissionCommand(
                new AdmissionIdentity(
                        new AdmissionIdentity.CommandId(commandId),
                        new AdmissionIdentity.OrderId(orderId),
                        new AdmissionIdentity.AccountId(accountId)),
                new AdmissionOrder(
                        new AdmissionOrder.Instrument(
                                new AdmissionOrder.Symbol(command.getInstrument().getSymbol()),
                                new AdmissionOrder.VenueMic(venue)),
                        new AdmissionOrder.Characteristics(
                                new AdmissionOrder.SideCode(command.getSide().name()),
                                new AdmissionOrder.Quantity(command.getQuantity().getShares()),
                                new AdmissionOrder.LimitPriceUnits(limitPrice),
                                new AdmissionOrder.OrderTypeCode(command.getOrderType().name()),
                                new AdmissionOrder.TimeInForceCode(command.getTif().name())),
                        tradingDay),
                new AdmissionFixIdentity(
                        new AdmissionFixIdentity.SenderCompId(command.getSenderCompId()),
                        new AdmissionFixIdentity.TargetCompId(command.getTargetCompId()),
                        new AdmissionFixIdentity.ClOrdId(command.getClOrdId())),
                new AdmissionRoutingReference(
                        new AdmissionRoutingReference.RoutingSnapshotId(routingSnapshotId)));
    }

    /**
     * Converts a valid cancel command into the same durable identity carrier.
     */
    @SuppressWarnings("PMD.CyclomaticComplexity") // Cancel validation is an indivisible admission policy.
    public AdmissionCommand validateCancel(CancelOrderCommand command) {
        if (command == null) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("cancel command is required"));
        }
        final UUID commandId = uuid(command.getCommandId(), "command_id");
        final UUID orderId = uuid(command.getOrderId(), "order_id");
        final UUID accountId = uuid(command.getAccountId(), "account_id");
        if (command.getInstrument().getSymbol().isBlank()
                || (!"XTAI".equals(command.getInstrument().getVenueMic())
                && !"ROCO".equals(command.getInstrument().getVenueMic()))) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidInstrument("symbol and supported venue are required"));
        }
        if (command.getSide() == Side.SIDE_UNSPECIFIED || command.getOrigClOrdId().isBlank()
                || command.getSenderCompId().isBlank() || command.getTargetCompId().isBlank()
                || command.getClOrdId().isBlank()) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("cancel identity and side are required"));
        }
        if (command.getSessionState() != SessionState.SESSION_STATE_CONTINUOUS) {
            throw new AdmissionValidationException(
                    AdmissionFailure.unsupportedSession("cancel admission requires CONTINUOUS session"));
        }
        final LocalDate tradingDay;
        try {
            tradingDay = LocalDate.parse(command.getTradingDay().getIsoDate());
        } catch (DateTimeParseException exception) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand("trading_day must be ISO-8601"));
        }
        return new AdmissionCommand(
                new AdmissionIdentity(
                        new AdmissionIdentity.CommandId(commandId),
                        new AdmissionIdentity.OrderId(orderId),
                        new AdmissionIdentity.AccountId(accountId)),
                new AdmissionOrder(
                        new AdmissionOrder.Instrument(
                                new AdmissionOrder.Symbol(command.getInstrument().getSymbol()),
                                new AdmissionOrder.VenueMic(command.getInstrument().getVenueMic())),
                        new AdmissionOrder.Characteristics(
                                new AdmissionOrder.SideCode(command.getSide().name()),
                                new AdmissionOrder.Quantity(1),
                                new AdmissionOrder.LimitPriceUnits(null),
                                new AdmissionOrder.OrderTypeCode("CANCEL"),
                                new AdmissionOrder.TimeInForceCode("CANCEL")),
                        tradingDay),
                new AdmissionFixIdentity(
                        new AdmissionFixIdentity.SenderCompId(command.getSenderCompId()),
                        new AdmissionFixIdentity.TargetCompId(command.getTargetCompId()),
                        new AdmissionFixIdentity.ClOrdId(command.getClOrdId())),
                new AdmissionRoutingReference(
                        new AdmissionRoutingReference.RoutingSnapshotId(null)));
    }

    private UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand(field + " is required"));
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand(field + " must be a UUID"));
        }
    }

    private long positive(long value, String field) {
        if (value <= 0) {
            throw new AdmissionValidationException(
                    AdmissionFailure.invalidCommand(field + " must be positive"));
        }
        return value;
    }
}
