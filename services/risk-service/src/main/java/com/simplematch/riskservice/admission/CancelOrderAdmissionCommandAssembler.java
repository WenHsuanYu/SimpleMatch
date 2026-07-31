package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;

/** Assembles a validated cancel command behind the Admission validation seam. */
final class CancelOrderAdmissionCommandAssembler {
  private CancelOrderAdmissionCommandAssembler() {}

  static AdmissionCommand assemble(CancelOrderCommand command) {
    final AdmissionIdentity identity =
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(
                AdmissionFieldParser.uuid(command.getCommandId(), "command_id")),
            new AdmissionIdentity.OrderId(
                AdmissionFieldParser.uuid(command.getOrderId(), "order_id")),
            new AdmissionIdentity.AccountId(
                AdmissionFieldParser.uuid(command.getAccountId(), "account_id")));
    validateInstrument(command);
    validateIdentity(command);
    validateSession(command);
    return new AdmissionCommand(
        identity,
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
            AdmissionFieldParser.isoTradingDay(command.getTradingDay().getIsoDate())),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId(command.getSenderCompId()),
            new AdmissionFixIdentity.TargetCompId(command.getTargetCompId()),
            new AdmissionFixIdentity.ClOrdId(command.getClOrdId())),
        new AdmissionRoutingReference(new AdmissionRoutingReference.RoutingSnapshotId(null)));
  }

  private static void validateInstrument(CancelOrderCommand command) {
    if (command.getInstrument().getSymbol().isBlank()
        || (!"XTAI".equals(command.getInstrument().getVenueMic())
            && !"ROCO".equals(command.getInstrument().getVenueMic()))) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidInstrument("symbol and supported venue are required"));
    }
  }

  private static void validateIdentity(CancelOrderCommand command) {
    if (command.getSide() == Side.SIDE_UNSPECIFIED
        || command.getOrigClOrdId().isBlank()
        || command.getSenderCompId().isBlank()
        || command.getTargetCompId().isBlank()
        || command.getClOrdId().isBlank()) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand("cancel identity and side are required"));
    }
  }

  private static void validateSession(CancelOrderCommand command) {
    if (command.getSessionState() != SessionState.SESSION_STATE_CONTINUOUS) {
      throw new AdmissionValidationException(
          AdmissionFailure.unsupportedSession("cancel admission requires CONTINUOUS session"));
    }
  }
}
