package com.simplematch.persistence.store;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;

/** Converts validated Matching Event values to Persistence's compact SQL representation. */
final class MatchingEventSqlValues {
  private MatchingEventSqlValues() {}

  static int sideCode(Side side) {
    return side == Side.SIDE_BUY ? 1 : 2;
  }

  static String status(TradeLegState state) {
    return switch (state) {
      case TRADE_LEG_STATE_FILLED -> "FILLED";
      case TRADE_LEG_STATE_PARTIALLY_FILLED -> "PARTIALLY_FILLED";
      case TRADE_LEG_STATE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("validated trade leg state is required");
    };
  }
}
