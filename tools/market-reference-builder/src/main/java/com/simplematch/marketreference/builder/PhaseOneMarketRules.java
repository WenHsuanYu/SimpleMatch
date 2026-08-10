package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.MarketRule;
import com.simplematch.marketreference.MarketRules;
import com.simplematch.marketreference.TickBandDefinition;
import com.simplematch.marketreference.TickTableDefinition;
import java.util.List;

/** Static Phase 1 Taiwan cash-market rules used by every eligible common stock. */
final class PhaseOneMarketRules {
  static final String REGULAR_BOARD_COMMON_STOCK = "regular-board-common-stock";
  private static final String RULE_SET_VERSION = "phase1-tw-cash-v1";
  private static final String TICK_TABLE_ID = "twd-standard-v1";
  private static final int BOARD_LOT_SHARES = 1_000;

  private PhaseOneMarketRules() {}

  static MarketRules marketRules() {
    return new MarketRules(
        RULE_SET_VERSION,
        "TWD",
        List.of(new MarketRule(REGULAR_BOARD_COMMON_STOCK, BOARD_LOT_SHARES, TICK_TABLE_ID)),
        List.of(
            new TickTableDefinition(
                TICK_TABLE_ID,
                List.of(
                    new TickBandDefinition(50_000L, 100L),
                    new TickBandDefinition(100_000L, 500L),
                    new TickBandDefinition(500_000L, 1_000L),
                    new TickBandDefinition(1_000_000L, 5_000L),
                    new TickBandDefinition(5_000_000L, 10_000L),
                    new TickBandDefinition(10_000_000L, 50_000L),
                    new TickBandDefinition(null, 100_000L)))));
  }
}
