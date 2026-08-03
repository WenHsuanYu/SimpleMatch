package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;

/** Gateway-local order terms required by a new-order WAL command. */
public record WalOrderTerms(
    String symbol,
    Side side,
    String quantity,
    String price,
    OrderType orderType,
    TimeInForce tif) {
  /** Requires a normalized, locally complete new-order payload. */
  public WalOrderTerms {
    symbol = WalValidation.requiredText(symbol, "symbol");
    if (side == null || side == Side.SIDE_UNSPECIFIED) {
      throw new IllegalArgumentException("side must be specified");
    }
    quantity = WalValidation.positiveDecimal(quantity, "quantity");
    orderType = requireOrderType(orderType);
    price = WalValidation.optionalPositiveDecimal(price, "price");
    if (orderType == OrderType.ORDER_TYPE_LIMIT && price.isBlank()) {
      throw new IllegalArgumentException("price is required for a limit order");
    }
    tif = requireTimeInForce(tif);
  }

  private static OrderType requireOrderType(OrderType value) {
    if (value == null
        || (value != OrderType.ORDER_TYPE_LIMIT && value != OrderType.ORDER_TYPE_MARKET)) {
      throw new IllegalArgumentException("order_type must be limit or market");
    }
    return value;
  }

  private static TimeInForce requireTimeInForce(TimeInForce value) {
    if (value == null || value == TimeInForce.TIME_IN_FORCE_UNSPECIFIED) {
      throw new IllegalArgumentException("tif must be specified");
    }
    return value;
  }
}
