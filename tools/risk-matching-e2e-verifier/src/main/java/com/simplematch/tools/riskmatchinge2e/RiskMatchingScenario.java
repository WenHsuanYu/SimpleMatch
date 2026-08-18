package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.common.v2.TwdPrice;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.ShareQuantity;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.MarketRule;
import com.simplematch.marketreference.RoutingAssignment;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure scenario construction and invariant checks for the RM-1 deployed verification.
 *
 * <p>This class deliberately contains no Kubernetes, PostgreSQL, gRPC-channel, or Kafka-consumer
 * code. Keeping the business assertions here makes the deployed shell harness an orchestrator
 * instead of a second implementation of Risk routing or Matching command semantics.
 */
public final class RiskMatchingScenario {
  private static final String SOURCE_SERVICE = "risk-matching-e2e-verifier";
  private static final String SENDER_COMP_ID = "RM1-E2E";
  private static final String TARGET_COMP_ID = "SIMPLEMATCH";

  private RiskMatchingScenario() {}

  /**
   * Builds one deterministic, valid BUY/LIMIT/ROD order from the mounted final artifact.
   *
   * <p>The instrument is selected by the canonical venue/symbol ordering already owned by the
   * artifact model. The verifier never hashes a symbol or invents a partition: it reads the
   * explicit {@link RoutingAssignment} and later requires Risk and Kafka to preserve it.
   */
  public static Scenario create(
      VerifiedMarketReferenceArtifact verified,
      LocalDate tradingDay,
      UUID accountId,
      String runId,
      Instant now) {
    Objects.requireNonNull(verified, "verified artifact is required");
    Objects.requireNonNull(tradingDay, "trading day is required");
    Objects.requireNonNull(accountId, "account id is required");
    Objects.requireNonNull(runId, "run id is required");
    Objects.requireNonNull(now, "clock instant is required");

    final MarketReferenceArtifact artifact = verified.artifact();
    if (!artifact.metadata().tradingDay().equals(tradingDay)) {
      throw new IllegalArgumentException(
          "artifact trading day "
              + artifact.metadata().tradingDay()
              + " does not match requested "
              + tradingDay);
    }

    final ArtifactInstrument instrument =
        artifact.marketSnapshot().instruments().stream()
            .filter(candidate -> candidate.eligibility() == InstrumentEligibility.ELIGIBLE)
            .filter(candidate -> candidate.referencePriceUnits() != null)
            .filter(candidate -> candidate.lowerPriceLimitUnits() != null)
            .filter(candidate -> candidate.upperPriceLimitUnits() != null)
            .min(Comparator.comparing(ArtifactInstrument::instrument))
            .orElseThrow(
                () -> new IllegalStateException("artifact contains no eligible final-price instrument"));

    final RoutingAssignment route =
        artifact.routingPolicy().assignments().stream()
            .filter(candidate -> candidate.instrument().equals(instrument.instrument()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "eligible instrument has no explicit artifact routing assignment"));

    final MarketRule rule =
        artifact.marketRules().rules().stream()
            .filter(candidate -> candidate.ruleId().equals(instrument.marketRuleId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "eligible instrument references a missing market rule: "
                            + instrument.marketRuleId()));

    final long quantityShares = rule.boardLotShares();
    final long limitPriceUnits = instrument.referencePriceUnits();
    final long estimatedNotionalUnits = Math.multiplyExact(quantityShares, limitPriceUnits);
    final UUID commandId = stableUuid(runId + ":command");
    final UUID orderId = stableUuid(runId + ":order");
    final UUID eventId = stableUuid(runId + ":event");

    final NewOrderCommand request =
        NewOrderCommand.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v2")
                    .setEventId(eventId.toString())
                    .setCreatedAtUnixMs(now.toEpochMilli())
                    .setSourceService(SOURCE_SERVICE)
                    .setCorrelationId(commandId.toString())
                    .build())
            .setCommandId(commandId.toString())
            .setOrderId(orderId.toString())
            .setAccountId(accountId.toString())
            .setInstrument(
                VenueInstrument.newBuilder()
                    .setSymbol(instrument.instrument().symbol())
                    .setVenueMic(instrument.instrument().venueMic())
                    .build())
            .setSide(Side.SIDE_BUY)
            .setQuantity(ShareQuantity.newBuilder().setShares(quantityShares).build())
            .setLimitPrice(TwdPrice.newBuilder().setUnits(limitPriceUnits).build())
            .setOrderType(OrderType.ORDER_TYPE_LIMIT)
            .setTif(TimeInForce.TIME_IN_FORCE_ROD)
            .setCurrency(Currency.CURRENCY_TWD)
            .setTradingDay(TradingDay.newBuilder().setIsoDate(tradingDay.toString()).build())
            .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
            // RM-1 owns routing from the startup artifact. Leaving this legacy input blank is
            // intentional: Risk must not trust a caller-supplied runtime routing snapshot.
            .setRoutingSnapshotId("")
            .setEstimatedNotional(
                TwdNotional.newBuilder().setUnits(estimatedNotionalUnits).build())
            .setSenderCompId(SENDER_COMP_ID)
            .setTargetCompId(TARGET_COMP_ID)
            .setClOrdId("RM1-" + commandId)
            .build();

    return new Scenario(
        runId,
        tradingDay,
        verified.identity().contentSha256(),
        artifact.routingPolicy().algorithmVersion(),
        route.partitionId(),
        instrument,
        rule,
        commandId,
        orderId,
        accountId,
        request);
  }

  /**
   * Requires the synchronous Risk response to agree with the artifact-derived route and identities.
   */
  public static void validateAcceptedResponse(
      Scenario scenario, OrderAdmissionResponse response) {
    Objects.requireNonNull(scenario, "scenario is required");
    Objects.requireNonNull(response, "response is required");
    if (!response.hasAccepted()) {
      final String detail =
          response.hasRejected()
              ? response.getRejected().getReasonDetail()
              : "response contains no terminal outcome";
      throw new IllegalStateException("Risk did not accept RM-1 E2E order: " + detail);
    }

    final var accepted = response.getAccepted();
    requireEquals(scenario.commandId().toString(), accepted.getCommandId(), "accepted command_id");
    requireEquals(scenario.orderId().toString(), accepted.getOrderId(), "accepted order_id");
    requireEquals(scenario.accountId().toString(), accepted.getAccountId(), "accepted account_id");
    if (accepted.getRoutingPartition() != scenario.expectedPartition()) {
      throw new IllegalStateException(
          "Risk accepted partition "
              + accepted.getRoutingPartition()
              + " but artifact assigned "
              + scenario.expectedPartition());
    }
  }

  /**
   * Requires the Kafka value to be the exact logical command admitted by Risk.
   *
   * <p>This assertion intentionally checks the artifact identity and routing algorithm in the
   * command header. A record with the right order fields but a recomputed or stale route is an
   * RM-1 failure, not a successful delivery.
   */
  public static void validateMatchingCommand(Scenario scenario, MatchingCommand command) {
    Objects.requireNonNull(scenario, "scenario is required");
    Objects.requireNonNull(command, "matching command is required");
    if (!command.hasNewOrder()) {
      throw new IllegalStateException("matching.commands record is not a NewOrder");
    }

    final var header = command.getHeader();
    final var order = command.getNewOrder();
    requireEquals(scenario.commandId().toString(), header.getCommandId(), "header command_id");
    if (header.getPartitionId() != scenario.expectedPartition()) {
      throw new IllegalStateException(
          "MatchingCommand partition "
              + header.getPartitionId()
              + " does not equal artifact partition "
              + scenario.expectedPartition());
    }
    requireEquals(
        scenario.tradingDay() + "-regular",
        header.getTradingSessionId(),
        "header trading_session_id");
    requireEquals(
        scenario.tradingDay().toString(),
        header.getArtifactIdentity().getTradingDay(),
        "artifact trading_day");
    requireEquals(
        scenario.artifactContentSha256(),
        header.getArtifactIdentity().getContentSha256(),
        "artifact content_sha256");
    requireEquals(
        scenario.routingAlgorithmVersion(),
        header.getRoutingAlgorithmVersion(),
        "routing algorithm version");

    requireEquals(scenario.orderId().toString(), order.getOrderId(), "new-order order_id");
    requireEquals(scenario.accountId().toString(), order.getAccountId(), "new-order account_id");
    requireEquals(
        scenario.instrument().instrument().symbol(),
        order.getInstrument().getSymbol(),
        "new-order symbol");
    requireEquals(
        scenario.instrument().instrument().venueMic(),
        order.getInstrument().getVenueMic(),
        "new-order venue_mic");
    if (order.getSide() != Side.SIDE_BUY) {
      throw new IllegalStateException("new-order side changed after Risk admission");
    }
    if (order.getQuantityShares() != scenario.rule().boardLotShares()) {
      throw new IllegalStateException("new-order quantity changed after Risk admission");
    }
    if (order.getLimitPriceUnits() != scenario.instrument().referencePriceUnits()) {
      throw new IllegalStateException("new-order limit price changed after Risk admission");
    }
    if (order.getOrderType() != OrderType.ORDER_TYPE_LIMIT) {
      throw new IllegalStateException("new-order type changed after Risk admission");
    }
    if (order.getTimeInForce() != TimeInForce.TIME_IN_FORCE_ROD) {
      throw new IllegalStateException("new-order time-in-force changed after Risk admission");
    }
  }

  private static UUID stableUuid(String material) {
    return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireEquals(String expected, String actual, String field) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalStateException(
          field + " mismatch: expected=" + expected + ", actual=" + actual);
    }
  }

  /**
   * Immutable expected facts shared by the gRPC and Kafka halves of one verifier run.
   *
   * <p>The record contains the original protobuf request so evidence generation can serialize the
   * exact values that were sent instead of reconstructing them later.
   */
  public record Scenario(
      String runId,
      LocalDate tradingDay,
      String artifactContentSha256,
      String routingAlgorithmVersion,
      int expectedPartition,
      ArtifactInstrument instrument,
      MarketRule rule,
      UUID commandId,
      UUID orderId,
      UUID accountId,
      NewOrderCommand request) {
    /** Requires a complete, routable scenario. */
    public Scenario {
      Objects.requireNonNull(runId, "run id is required");
      Objects.requireNonNull(tradingDay, "trading day is required");
      Objects.requireNonNull(artifactContentSha256, "artifact checksum is required");
      Objects.requireNonNull(routingAlgorithmVersion, "routing algorithm version is required");
      Objects.requireNonNull(instrument, "instrument is required");
      Objects.requireNonNull(rule, "market rule is required");
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(orderId, "order id is required");
      Objects.requireNonNull(accountId, "account id is required");
      Objects.requireNonNull(request, "request is required");
      if (expectedPartition < 0 || expectedPartition >= 15) {
        throw new IllegalArgumentException("expected partition must be in [0, 14]");
      }
    }
  }
}
