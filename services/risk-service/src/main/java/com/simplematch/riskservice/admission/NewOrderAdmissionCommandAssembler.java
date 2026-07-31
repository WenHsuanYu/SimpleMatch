package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import java.util.UUID;

/** Assembles a validated new-order command behind the Admission validation seam. */
final class NewOrderAdmissionCommandAssembler {
  private NewOrderAdmissionCommandAssembler() {}

  static AdmissionCommand assemble(NewOrderCommand command) {
    final AdmissionIdentity identity =
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(
                AdmissionFieldParser.uuid(command.getCommandId(), "command_id")),
            new AdmissionIdentity.OrderId(
                AdmissionFieldParser.uuid(command.getOrderId(), "order_id")),
            new AdmissionIdentity.AccountId(
                AdmissionFieldParser.uuid(command.getAccountId(), "account_id")));
    final String venue = validateInstrument(command);
    validateCharacteristics(command);
    validateSession(command);
    final Long limitPrice =
        command.getOrderType() == OrderType.ORDER_TYPE_LIMIT
            ? AdmissionFieldParser.positive(command.getLimitPrice().getUnits(), "limit_price")
            : null;
    final AdmissionOrder order =
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
            AdmissionFieldParser.requiredTradingDay(command.getTradingDay().getIsoDate()));
    validateFixIdentity(command);
    final UUID routingSnapshotId =
        command.getRoutingSnapshotId().isBlank()
            ? null
            : AdmissionFieldParser.uuid(command.getRoutingSnapshotId(), "routing_snapshot_id");
    return new AdmissionCommand(
        identity,
        order,
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId(command.getSenderCompId()),
            new AdmissionFixIdentity.TargetCompId(command.getTargetCompId()),
            new AdmissionFixIdentity.ClOrdId(command.getClOrdId())),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(routingSnapshotId)));
  }

  private static String validateInstrument(NewOrderCommand command) {
    if (command.getInstrument().getSymbol().isBlank()) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidInstrument("symbol is required"));
    }
    final String venue = command.getInstrument().getVenueMic();
    if (!"XTAI".equals(venue) && !"ROCO".equals(venue)) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidInstrument("venue_mic must be XTAI or ROCO"));
    }
    return venue;
  }

  private static void validateCharacteristics(NewOrderCommand command) {
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
  }

  private static void validateSession(NewOrderCommand command) {
    if (command.getSessionState() != SessionState.SESSION_STATE_CONTINUOUS) {
      throw new AdmissionValidationException(
          AdmissionFailure.unsupportedSession("new-order admission requires CONTINUOUS session"));
    }
  }

  private static void validateFixIdentity(NewOrderCommand command) {
    if (command.getSenderCompId().isBlank()
        || command.getTargetCompId().isBlank()
        || command.getClOrdId().isBlank()) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand("sender, target, and cl_ord_id are required"));
    }
  }
}
