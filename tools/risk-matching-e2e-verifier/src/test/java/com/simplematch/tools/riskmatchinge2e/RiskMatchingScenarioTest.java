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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Focused contract tests for the pure RM-1 verifier assertions. */
class RiskMatchingScenarioTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void acceptsMatchingCommandWithSameArtifactRouteAndOrderSemantics() {
    final RiskMatchingScenario.Scenario scenario = scenario();

    assertDoesNotThrow(
        () -> RiskMatchingScenario.validateMatchingCommand(scenario, matchingCommand(scenario, HASH, 7)));
  }

  @Test
  void rejectsMatchingCommandWhenArtifactIdentityChanges() {
    final RiskMatchingScenario.Scenario scenario = scenario();

    assertThrows(
        IllegalStateException.class,
        () ->
            RiskMatchingScenario.validateMatchingCommand(
                scenario, matchingCommand(scenario, "b".repeat(64), 7)));
  }

  @Test
  void rejectsRiskResponseWhenPartitionDiffersFromArtifactAssignment() {
    final RiskMatchingScenario.Scenario scenario = scenario();
    final OrderAdmissionResponse response =
        OrderAdmissionResponse.newBuilder()
            .setAccepted(
                OrderAdmissionAccepted.newBuilder()
                    .setCommandId(scenario.commandId().toString())
                    .setOrderId(scenario.orderId().toString())
                    .setAccountId(scenario.accountId().toString())
                    .setRoutingPartition(6)
                    .build())
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> RiskMatchingScenario.validateAcceptedResponse(scenario, response));
  }

  private static RiskMatchingScenario.Scenario scenario() {
    final UUID commandId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    final InstrumentRef ref = new InstrumentRef("XTAI", "2330");
    final ArtifactInstrument instrument =
        new ArtifactInstrument(
            ref, InstrumentEligibility.ELIGIBLE, null, "TWD-EQUITY", 10_000L, 9_000L, 11_000L);
    final MarketRule rule = new MarketRule("TWD-EQUITY", 1_000, "TWSE-TICK");

    return new RiskMatchingScenario.Scenario(
        "test-run",
        LocalDate.of(2026, 8, 17),
        HASH,
        "stable-least-loaded-v1",
        7,
        instrument,
        rule,
        commandId,
        orderId,
        accountId,
        NewOrderCommand.newBuilder()
            .setCommandId(commandId.toString())
            .setOrderId(orderId.toString())
            .setAccountId(accountId.toString())
            .build());
  }

  private static MatchingCommand matchingCommand(
      RiskMatchingScenario.Scenario scenario, String checksum, int partition) {
    return MatchingCommand.newBuilder()
        .setHeader(
            CommandHeader.newBuilder()
                .setSchemaVersion(1)
                .setCommandId(scenario.commandId().toString())
                .setTradingSessionId("2026-08-17-regular")
                .setPartitionId(partition)
                .setArtifactIdentity(
                    ArtifactIdentity.newBuilder()
                        .setTradingDay("2026-08-17")
                        .setContentSha256(checksum))
                .setRoutingAlgorithmVersion("stable-least-loaded-v1"))
        .setNewOrder(
            NewOrder.newBuilder()
                .setOrderId(scenario.orderId().toString())
                .setAccountId(scenario.accountId().toString())
                .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                .setSide(Side.SIDE_BUY)
                .setQuantityShares(1_000)
                .setLimitPriceUnits(10_000)
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .setTimeInForce(TimeInForce.TIME_IN_FORCE_ROD))
        .build();
  }
}
