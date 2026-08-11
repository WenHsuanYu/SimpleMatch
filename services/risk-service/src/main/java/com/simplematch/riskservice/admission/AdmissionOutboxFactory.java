package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CancelOrder;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.matching.runtime.v1.NewOrder;
import com.simplematch.riskservice.outbox.OutboxRecord;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Creates final artifact-routed Matching commands for accepted Risk Admission outcomes. */
public final class AdmissionOutboxFactory {
  private final String topic;
  private final Clock clock;

  /** Creates an outbox factory over the configured topic and terminal-event clock. */
  public AdmissionOutboxFactory(String topic, Clock clock) {
    this.topic = Objects.requireNonNull(topic, "topic");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Builds the terminal Matching command for an accepted journal outcome.
   *
   * <p>Rejected admissions are reported synchronously to the gateway and intentionally create no
   * Matching command. That prevents a rejected risk outcome from becoming a Matching input.
   */
  public Optional<OutboxRecord> create(AdmissionJournalEntry entry) {
    Objects.requireNonNull(entry, "entry");
    if (entry.lifecycle().state() == AdmissionState.REJECTED) {
      return Optional.empty();
    }
    if (entry.lifecycle().state() != AdmissionState.ACCEPTED) {
      throw new IllegalArgumentException("only terminal admissions can create matching commands");
    }
    final AdmissionCommand command = entry.command();
    final AdmissionIdentity identity = command.identity();
    final long now = clock.millis();
    final int routingPartition = entry.route().requireAssignedPartition();
    final String commandId = identity.commandId().value().toString();
    final MatchingCommand matchingCommand = matchingCommand(entry);
    return Optional.of(
        OutboxRecord.create(
            new OutboxRecord.EventInfo(commandId, now),
            OutboxRecord.Routing.withPartition(topic, commandId, routingPartition),
            new OutboxRecord.PayloadEnvelope(
                matchingCommand.toByteArray(),
                MatchingCommand.getDescriptor().getFullName(),
                "{\"schema_version\":\"matching-command-v1\"}"),
            new OutboxRecord.AggregateRef(
                "order_admission", identity.orderId().value().toString())));
  }

  private static MatchingCommand matchingCommand(AdmissionJournalEntry entry) {
    final AdmissionCommand command = entry.command();
    final AdmissionIdentity identity = command.identity();
    final AdmissionOrder order = command.order();
    final var route = entry.route();
    final var artifact = route.requireArtifactIdentity();
    final CommandHeader header =
        CommandHeader.newBuilder()
            .setSchemaVersion(1)
            .setCommandId(identity.commandId().value().toString())
            .setTradingSessionId(artifact.tradingDay() + "-regular")
            .setPartitionId(route.requireAssignedPartition())
            .setArtifactIdentity(
                ArtifactIdentity.newBuilder()
                    .setTradingDay(artifact.tradingDay().toString())
                    .setContentSha256(artifact.contentSha256()))
            .setRoutingAlgorithmVersion(route.requireRoutingAlgorithmVersion())
            .build();
    if (order.isCancellation()) {
      return MatchingCommand.newBuilder()
          .setHeader(header)
          .setCancelOrder(
              CancelOrder.newBuilder()
                  .setOrderId(identity.orderId().value().toString())
                  .setAccountId(identity.accountId().value().toString())
                  .setInstrument(instrument(order))
                  .setSide(side(order.characteristics().side().value())))
          .build();
    }
    return MatchingCommand.newBuilder()
        .setHeader(header)
        .setNewOrder(
            NewOrder.newBuilder()
                .setOrderId(identity.orderId().value().toString())
                .setAccountId(identity.accountId().value().toString())
                .setInstrument(instrument(order))
                .setSide(side(order.characteristics().side().value()))
                .setQuantityShares(order.characteristics().quantity().value())
                .setLimitPriceUnits(
                    order.characteristics().limitPrice().value() == null
                        ? 0L
                        : order.characteristics().limitPrice().value())
                .setOrderType(orderType(order.characteristics().orderType().value()))
                .setTimeInForce(timeInForce(order.characteristics().timeInForce().value())))
        .build();
  }

  private static VenueInstrument instrument(AdmissionOrder order) {
    return VenueInstrument.newBuilder()
        .setSymbol(order.instrument().symbol().value())
        .setVenueMic(order.instrument().venueMic().value())
        .build();
  }

  private static Side side(String value) {
    return switch (value) {
      case "SIDE_BUY", "BUY" -> Side.SIDE_BUY;
      case "SIDE_SELL", "SELL" -> Side.SIDE_SELL;
      default -> throw new IllegalArgumentException("unsupported admission side: " + value);
    };
  }

  private static OrderType orderType(String value) {
    return switch (value) {
      case "ORDER_TYPE_LIMIT", "LIMIT" -> OrderType.ORDER_TYPE_LIMIT;
      case "ORDER_TYPE_MARKET", "MARKET" -> OrderType.ORDER_TYPE_MARKET;
      default -> throw new IllegalArgumentException("unsupported admission order type: " + value);
    };
  }

  private static TimeInForce timeInForce(String value) {
    return switch (value) {
      case "TIME_IN_FORCE_ROD", "ROD" -> TimeInForce.TIME_IN_FORCE_ROD;
      case "TIME_IN_FORCE_IOC", "IOC" -> TimeInForce.TIME_IN_FORCE_IOC;
      case "TIME_IN_FORCE_FOK", "FOK" -> TimeInForce.TIME_IN_FORCE_FOK;
      default ->
          throw new IllegalArgumentException("unsupported admission time-in-force: " + value);
    };
  }
}
