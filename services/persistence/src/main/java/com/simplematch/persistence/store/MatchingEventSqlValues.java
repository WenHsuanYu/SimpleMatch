package com.simplematch.persistence.store;

import com.simplematch.contracts.common.v2.Side;

/** Converts validated Matching Event values to Persistence's compact SQL representation. */
final class MatchingEventSqlValues {
  private MatchingEventSqlValues() {}

  static int sideCode(Side side) {
    return side == Side.SIDE_BUY ? 1 : 2;
  }

  static String statusForLeaves(long leavesQuantityShares) {
    return leavesQuantityShares == 0 ? "FILLED" : "PARTIALLY_FILLED";
  }
}
