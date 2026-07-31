package com.simplematch.riskservice.admission;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-independent validated command entering durable risk admission.
 *
 * <p>The command is composed from identity, order facts, FIX business identity, and routing
 * reference. Same-shaped UUID and string fields use different Java types, so the canonical
 * constructor cannot accept a command identifier where an order or account identifier belongs.
 *
 * @param identity the command, order, and account identities
 * @param order the validated instrument, order characteristics, and trading day
 * @param fixIdentity the FIX business identity used for idempotency
 * @param routing the optional market-routing snapshot reference
 */
public record AdmissionCommand(
    AdmissionIdentity identity,
    AdmissionOrder order,
    AdmissionFixIdentity fixIdentity,
    AdmissionRoutingReference routing) {
  /** Requires all four domain components. */
  public AdmissionCommand {
    identity = Objects.requireNonNull(identity, "identity");
    order = Objects.requireNonNull(order, "order");
    fixIdentity = Objects.requireNonNull(fixIdentity, "fixIdentity");
    routing = Objects.requireNonNull(routing, "routing");
  }

  /**
   * Creates a compatibility command from the former flat parameter list.
   *
   * @deprecated Use the composed domain constructor; retained for journal-recovery compatibility
   *     while positional callers migrate. Remove with {@code V1AdmissionCompatibilityAdapter}.
   */
  @Deprecated(forRemoval = false)
  @SuppressWarnings({"PMD.ExcessiveParameterList", "checkstyle:ParameterNumber"})
  public AdmissionCommand(
      UUID commandId,
      UUID orderId,
      UUID accountId,
      String symbol,
      String venueMic,
      String side,
      long quantity,
      Long limitPriceUnits,
      String orderType,
      String tif,
      LocalDate tradingDay,
      String senderCompId,
      String targetCompId,
      String clOrdId,
      UUID routingSnapshotId) {
    this(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(commandId),
            new AdmissionIdentity.OrderId(orderId),
            new AdmissionIdentity.AccountId(accountId)),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol(symbol), new AdmissionOrder.VenueMic(venueMic)),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode(side),
                new AdmissionOrder.Quantity(quantity),
                new AdmissionOrder.LimitPriceUnits(limitPriceUnits),
                new AdmissionOrder.OrderTypeCode(orderType),
                new AdmissionOrder.TimeInForceCode(tif)),
            tradingDay),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId(senderCompId),
            new AdmissionFixIdentity.TargetCompId(targetCompId),
            new AdmissionFixIdentity.ClOrdId(clOrdId)),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(routingSnapshotId)));
  }
}
