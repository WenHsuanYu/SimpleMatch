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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
   *
   * @param verified the verified market-reference artifact
   * @param run the identity shared by this verifier run
   * @param now the timestamp used for generated event metadata
   * @return a complete scenario for Risk admission and Kafka verification
   */
  public static Scenario create(
      VerifiedMarketReferenceArtifact verified,
      RunIdentity run,
      Instant now) {
    return create(verified, run, now, Side.SIDE_BUY);
  }

  /**
   * Builds one deterministic, valid LIMIT/ROD order for the requested side.
   *
   * <p>The side is the only scenario fact that varies between the two public RM-1 fixture orders:
   * a BUY can reserve the account's daily cash limit and a SELL can reserve its long position.
   * Both orders still derive instrument, price, quantity, and routing from the same verified
   * artifact so Matching can produce one execution without bypassing Risk or Account.
   *
   * @param verified the verified market-reference artifact
   * @param run the identity shared by this verifier run
   * @param now the timestamp used for generated event metadata
   * @param side the supported order side
   * @return a complete scenario for Risk admission and Kafka verification
   */
  public static Scenario create(
      VerifiedMarketReferenceArtifact verified,
      RunIdentity run,
      Instant now,
      Side side) {
    Objects.requireNonNull(verified, "verified artifact is required");
    Objects.requireNonNull(run, "run identity is required");
    Objects.requireNonNull(now, "clock instant is required");
    requireSupportedSide(side);

    final MarketReferenceArtifact artifact = verified.artifact();
    if (!artifact.metadata().tradingDay().equals(run.tradingDay())) {
      throw new IllegalArgumentException(
          "artifact trading day "
              + artifact.metadata().tradingDay()
              + " does not match requested "
              + run.tradingDay());
    }

    final ArtifactInstrument instrument =
        artifact.marketSnapshot().instruments().stream()
            .filter(candidate -> candidate.eligibility() == InstrumentEligibility.ELIGIBLE)
            .filter(candidate -> candidate.referencePriceUnits() != null)
            .filter(candidate -> candidate.lowerPriceLimitUnits() != null)
            .filter(candidate -> candidate.upperPriceLimitUnits() != null)
            .min(Comparator.comparing(ArtifactInstrument::instrument))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "artifact contains no eligible final-price instrument"));

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
    final long estimatedNotionalUnits =
        Math.multiplyExact(quantityShares, limitPriceUnits);

    final CommandIdentity command =
        new CommandIdentity(
            stableUuid(run.runId() + ":command"),
            stableUuid(run.runId() + ":order"));

    final UUID eventId = stableUuid(run.runId() + ":event");

    final NewOrderCommand request =
        NewOrderCommand.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v2")
                    .setEventId(eventId.toString())
                    .setCreatedAtUnixMs(now.toEpochMilli())
                    .setSourceService(SOURCE_SERVICE)
                    .setCorrelationId(command.commandId().toString())
                    .build())
            .setCommandId(command.commandId().toString())
            .setOrderId(command.orderId().toString())
            .setAccountId(run.accountId().toString())
            .setInstrument(
                VenueInstrument.newBuilder()
                    .setSymbol(instrument.instrument().symbol())
                    .setVenueMic(instrument.instrument().venueMic())
                    .build())
            .setSide(side)
            .setQuantity(
                ShareQuantity.newBuilder()
                    .setShares(quantityShares)
                    .build())
            .setLimitPrice(
                TwdPrice.newBuilder()
                    .setUnits(limitPriceUnits)
                    .build())
            .setOrderType(OrderType.ORDER_TYPE_LIMIT)
            .setTif(TimeInForce.TIME_IN_FORCE_ROD)
            .setCurrency(Currency.CURRENCY_TWD)
            .setTradingDay(
                TradingDay.newBuilder()
                    .setIsoDate(run.tradingDay().toString())
                    .build())
            .setSessionState(SessionState.SESSION_STATE_CONTINUOUS)
            // RM-1 owns routing from the startup artifact. Leaving this legacy input blank is
            // intentional: Risk must not trust a caller-supplied runtime routing snapshot.
            .setRoutingSnapshotId("")
            .setEstimatedNotional(
                TwdNotional.newBuilder()
                    .setUnits(estimatedNotionalUnits)
                    .build())
            .setSenderCompId(SENDER_COMP_ID)
            .setTargetCompId(TARGET_COMP_ID)
            .setClOrdId("RM1-" + command.commandId())
            .build();

    final MarketExpectation market =
        new MarketExpectation(
            verified.identity().contentSha256(),
            artifact.routingPolicy().algorithmVersion(),
            route.partitionId(),
            instrument,
            rule);

    return new Scenario(run, market, command, request);
  }

  /**
   * Requires the synchronous Risk response to agree with the artifact-derived route and identities.
   *
   * @param scenario the expected verifier scenario
   * @param response the synchronous response returned by Risk
   */
  public static void validateAcceptedResponse(
      Scenario scenario,
      OrderAdmissionResponse response) {
    Objects.requireNonNull(scenario, "scenario is required");
    Objects.requireNonNull(response, "response is required");

    if (!response.hasAccepted()) {
      final String detail =
          response.hasRejected()
              ? response.getRejected().getReasonDetail()
              : "response contains no terminal outcome";

      throw new IllegalStateException(
          "Risk did not accept RM-1 E2E order: " + detail);
    }

    final var accepted = response.getAccepted();
    final RunIdentity run = scenario.run();
    final CommandIdentity command = scenario.command();

    requireEquals(
        command.commandId().toString(),
        accepted.getCommandId(),
        "accepted command_id");
    requireEquals(
        command.orderId().toString(),
        accepted.getOrderId(),
        "accepted order_id");
    requireEquals(
        run.accountId().toString(),
        accepted.getAccountId(),
        "accepted account_id");

    if (accepted.getRoutingPartition() != scenario.market().expectedPartition()) {
      throw new IllegalStateException(
          "Risk accepted partition "
              + accepted.getRoutingPartition()
              + " but artifact assigned "
              + scenario.market().expectedPartition());
    }
  }

  /**
   * Requires the Kafka value to be the exact logical command admitted by Risk.
   *
   * <p>This assertion intentionally checks the artifact identity and routing algorithm in the
   * command header. A record with the right order fields but a recomputed or stale route is an
   * RM-1 failure, not a successful delivery.
   *
   * @param scenario the expected verifier scenario
   * @param command the Matching command decoded from Kafka
   */
  public static void validateMatchingCommand(
      Scenario scenario,
      MatchingCommand command) {
    Objects.requireNonNull(scenario, "scenario is required");
    Objects.requireNonNull(command, "matching command is required");

    if (!command.hasNewOrder()) {
      throw new IllegalStateException(
          "matching.commands record is not a NewOrder");
    }

    validateCommandHeader(scenario, command);
    validateNewOrder(scenario, command);
  }

  /** Requires the matching command header to preserve artifact and routing identity. */
  private static void validateCommandHeader(
      Scenario scenario,
      MatchingCommand command) {
    final var header = command.getHeader();
    final RunIdentity run = scenario.run();
    final MarketExpectation market = scenario.market();
    final CommandIdentity identity = scenario.command();

    requireEquals(
        identity.commandId().toString(),
        header.getCommandId(),
        "header command_id");
    requireLongEquals(
        market.expectedPartition(),
        header.getPartitionId(),
        "header partition_id");
    requireEquals(
        run.tradingDay() + "-regular",
        header.getTradingSessionId(),
        "header trading_session_id");
    requireEquals(
        run.tradingDay().toString(),
        header.getArtifactIdentity().getTradingDay(),
        "artifact trading_day");
    requireEquals(
        market.artifactContentSha256(),
        header.getArtifactIdentity().getContentSha256(),
        "artifact content_sha256");
    requireEquals(
        market.routingAlgorithmVersion(),
        header.getRoutingAlgorithmVersion(),
        "routing algorithm version");
  }

  /** Requires the NewOrder payload to preserve the order admitted by Risk. */
  private static void validateNewOrder(
      Scenario scenario,
      MatchingCommand command) {
    final var order = command.getNewOrder();
    final RunIdentity run = scenario.run();
    final MarketExpectation market = scenario.market();
    final CommandIdentity identity = scenario.command();

    requireEquals(
        identity.orderId().toString(),
        order.getOrderId(),
        "new-order order_id");
    requireEquals(
        run.accountId().toString(),
        order.getAccountId(),
        "new-order account_id");
    requireEquals(
        market.instrument().instrument().symbol(),
        order.getInstrument().getSymbol(),
        "new-order symbol");
    requireEquals(
        market.instrument().instrument().venueMic(),
        order.getInstrument().getVenueMic(),
        "new-order venue_mic");
    requireEquals(
        scenario.request().getSide(),
        order.getSide(),
        "new-order side");
    requireLongEquals(
        market.rule().boardLotShares(),
        order.getQuantityShares(),
        "new-order quantity_shares");
    requireLongEquals(
        market.instrument().referencePriceUnits(),
        order.getLimitPriceUnits(),
        "new-order limit_price_units");
    requireEquals(
        OrderType.ORDER_TYPE_LIMIT,
        order.getOrderType(),
        "new-order order_type");
    requireEquals(
        TimeInForce.TIME_IN_FORCE_ROD,
        order.getTimeInForce(),
        "new-order time_in_force");
  }

  /** Creates a deterministic UUID from the supplied UTF-8 material. */
  private static UUID stableUuid(String material) {
    return UUID.nameUUIDFromBytes(
        material.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Requires two values to be equal.
   *
   * @param expected the expected value
   * @param actual the observed value
   * @param field the field name used in the failure message
   */
  private static void requireEquals(
      Object expected,
      Object actual,
      String field) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalStateException(
          field
              + " mismatch: expected="
              + expected
              + ", actual="
              + actual);
    }
  }

  /**
   * Requires two integral values to be equal.
   *
   * @param expected the expected value
   * @param actual the observed value
   * @param field the field name used in the failure message
   */
  private static void requireLongEquals(
      long expected,
      long actual,
      String field) {
    if (expected != actual) {
      throw new IllegalStateException(
          field
              + " mismatch: expected="
              + expected
              + ", actual="
              + actual);
    }
  }

  private static void requireSupportedSide(Side side) {
    if (side != Side.SIDE_BUY && side != Side.SIDE_SELL) {
      throw new IllegalArgumentException("side must be BUY or SELL");
    }
  }

  /**
   * Immutable identity shared by all operations in one verifier run.
   *
   * @param runId the unique verifier run identifier
   * @param tradingDay the trading day verified by this run
   * @param accountId the account used for the generated order
   */
  public record RunIdentity(
      String runId,
      LocalDate tradingDay,
      UUID accountId) {

    /** Requires a complete verifier run identity. */
    public RunIdentity {
      Objects.requireNonNull(runId, "run id is required");
      Objects.requireNonNull(tradingDay, "trading day is required");
      Objects.requireNonNull(accountId, "account id is required");
    }
  }

  /**
   * Immutable market facts that the Risk and Kafka results must preserve.
   *
   * @param artifactContentSha256 the verified artifact content digest
   * @param routingAlgorithmVersion the routing algorithm version from the artifact
   * @param expectedPartition the artifact-assigned Kafka partition
   * @param instrument the artifact instrument selected for this scenario
   * @param rule the market rule referenced by the selected instrument
   */
  public record MarketExpectation(
      String artifactContentSha256,
      String routingAlgorithmVersion,
      int expectedPartition,
      ArtifactInstrument instrument,
      MarketRule rule) {

    /** Requires complete and routable expected market facts. */
    public MarketExpectation {
      Objects.requireNonNull(
          artifactContentSha256,
          "artifact checksum is required");
      Objects.requireNonNull(
          routingAlgorithmVersion,
          "routing algorithm version is required");
      Objects.requireNonNull(instrument, "instrument is required");
      Objects.requireNonNull(rule, "market rule is required");

      if (expectedPartition < 0 || expectedPartition >= 15) {
        throw new IllegalArgumentException(
            "expected partition must be in [0, 14]");
      }
    }
  }

  /**
   * Immutable identifiers generated for the command and order in one verifier run.
   *
   * @param commandId the deterministic command identifier
   * @param orderId the deterministic order identifier
   */
  public record CommandIdentity(
      UUID commandId,
      UUID orderId) {

    /** Requires both command and order identifiers. */
    public CommandIdentity {
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(orderId, "order id is required");
    }
  }

  /**
   * Immutable expected facts shared by the gRPC and Kafka halves of one verifier run.
   *
   * <p>The record contains the original protobuf request so evidence generation can serialize the
   * exact values that were sent instead of reconstructing them later.
   *
   * @param run the verifier run identity
   * @param market the artifact-derived market expectations
   * @param command the deterministic command and order identifiers
   * @param request the exact NewOrder request submitted to Risk
   */
  public record Scenario(
      RunIdentity run,
      MarketExpectation market,
      CommandIdentity command,
      NewOrderCommand request) {

    /** Requires a complete verifier scenario. */
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Generated protobuf messages are immutable after construction.")
    public Scenario {
      Objects.requireNonNull(run, "run identity is required");
      Objects.requireNonNull(market, "market expectation is required");
      Objects.requireNonNull(command, "command identity is required");
      Objects.requireNonNull(request, "request is required");
    }

    /**
     * Returns the exact immutable NewOrder protobuf submitted to Risk.
     *
     * @return the submitted NewOrder request
     */
    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Generated protobuf messages are immutable after construction.")
    public NewOrderCommand request() {
      return request;
    }
  }
}
