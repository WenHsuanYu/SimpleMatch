package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.matching.runtime.v1.NewOrder;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.MarketRule;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.CommandIdentity;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.MarketExpectation;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.RunIdentity;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.Scenario;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Focused contract tests for the pure RM-1 verifier assertions. */
class RiskMatchingScenarioTest {
  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);
  private static final String RUN_ID = "test-run";
  private static final String ROUTING_ALGORITHM_VERSION = "stable-least-loaded-v1";

  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 17);

  private static final int EXPECTED_PARTITION = 7;
  private static final int OTHER_PARTITION = 6;

  private static final UUID COMMAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void acceptsMatchingCommandWithSameArtifactRouteAndOrderSemantics() {
    final RiskMatchingScenario.Scenario scenario = scenario();

    assertDoesNotThrow(() -> RiskMatchingScenario.validateMatchingCommand(scenario,
        matchingCommand(scenario, HASH, scenario.market().expectedPartition())));
  }

  @Test
  void rejectsMatchingCommandWhenArtifactIdentityChanges() {
    final RiskMatchingScenario.Scenario scenario = scenario();

    assertThrows(IllegalStateException.class, () -> RiskMatchingScenario.validateMatchingCommand(
        scenario, matchingCommand(scenario, OTHER_HASH, scenario.market().expectedPartition())));
  }

  @Test
  void rejectsRiskResponseWhenPartitionDiffersFromArtifactAssignment() {
    final RiskMatchingScenario.Scenario scenario = scenario();

    final OrderAdmissionResponse response =
        OrderAdmissionResponse.newBuilder()
            .setAccepted(
                OrderAdmissionAccepted.newBuilder()
                    .setCommandId(
                        scenario.command()
                            .commandId()
                            .toString())
                    .setOrderId(
                        scenario.command()
                            .orderId()
                            .toString())
                    .setAccountId(
                        scenario.run()
                            .accountId()
                            .toString())
                    .setRoutingPartition(OTHER_PARTITION)
                    .build())
            .build();

    assertThrows(IllegalStateException.class, () -> RiskMatchingScenario.validateAcceptedResponse(
                scenario, response));
  }

  /**
   * Creates one complete expected scenario using the grouped verifier domain model.
   *
   * @return the scenario used by the contract assertions
   */
  private static RiskMatchingScenario.Scenario scenario() {
    final RunIdentity run = new RunIdentity(RUN_ID,TRADING_DAY, ACCOUNT_ID);

    final InstrumentRef ref = new InstrumentRef("XTAI", "2330");

    final ArtifactInstrument instrument = new ArtifactInstrument(ref, InstrumentEligibility.ELIGIBLE,
        null, "TWD-EQUITY", 10_000L, 9_000L, 11_000L);

    final MarketRule rule = new MarketRule("TWD-EQUITY", 1_000, "TWSE-TICK");

    final MarketExpectation market = new MarketExpectation(HASH, ROUTING_ALGORITHM_VERSION,
        EXPECTED_PARTITION, instrument, rule);

    final CommandIdentity command = new CommandIdentity(COMMAND_ID, ORDER_ID);

    final NewOrderCommand request =
        NewOrderCommand.newBuilder()
            .setCommandId(
                command.commandId().toString())
            .setOrderId(
                command.orderId().toString())
            .setAccountId(
                run.accountId().toString())
            .build();

    return new Scenario(run, market, command, request);
  }

  /**
   * Builds a MatchingCommand from the scenario while allowing selected header facts to vary.
   *
   * <p>The checksum and partition remain explicit parameters because individual tests intentionally
   * vary those values while all other command facts come from the scenario.
   *
   * @param scenario the expected scenario supplying order and routing facts
   * @param checksum the artifact checksum written to the command header
   * @param partition the partition written to the command header
   * @return the matching command used by a validation assertion
   */
  private static MatchingCommand matchingCommand(
      Scenario scenario, String checksum, int partition) {
    final RunIdentity run = scenario.run();

    final MarketExpectation market = scenario.market();

    final CommandIdentity command = scenario.command();

    return MatchingCommand.newBuilder()
        .setHeader(
            CommandHeader.newBuilder()
                .setSchemaVersion(1)
                .setCommandId(
                    command.commandId().toString())
                .setTradingSessionId(
                    run.tradingDay() + "-regular")
                .setPartitionId(partition)
                .setArtifactIdentity(
                    ArtifactIdentity.newBuilder()
                        .setTradingDay(
                            run.tradingDay().toString())
                        .setContentSha256(checksum))
                .setRoutingAlgorithmVersion(
                    market.routingAlgorithmVersion()))
        .setNewOrder(
            NewOrder.newBuilder()
                .setOrderId(
                    command.orderId().toString())
                .setAccountId(
                    run.accountId().toString())
                .setInstrument(
                    VenueInstrument.newBuilder()
                        .setVenueMic(
                            market.instrument()
                                .instrument()
                                .venueMic())
                        .setSymbol(
                            market.instrument()
                                .instrument()
                                .symbol()))
                .setSide(Side.SIDE_BUY)
                .setQuantityShares(
                    market.rule().boardLotShares())
                .setLimitPriceUnits(
                    market.instrument()
                        .referencePriceUnits())
                .setOrderType(
                    OrderType.ORDER_TYPE_LIMIT)
                .setTimeInForce(
                    TimeInForce.TIME_IN_FORCE_ROD))
        .build();
  }
}