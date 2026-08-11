package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.common.v2.Side;

/**
 * Converts validated order-book values to the compact SQL representation used by the projection.
 */
final class MarketDataSqlValues {
  private MarketDataSqlValues() {}

  static String sideCode(Side side) {
    return side == Side.SIDE_BUY ? "B" : "S";
  }
}
