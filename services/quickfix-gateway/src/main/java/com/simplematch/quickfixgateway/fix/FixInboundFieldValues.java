package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import quickfix.FieldMap;
import quickfix.FieldNotFound;

/** Converts optional inbound FIX fields into the gateway's normalized command values. */
final class FixInboundFieldValues {
  private FixInboundFieldValues() {}

  static Side mapSide(char value) {
    return value == '2' ? Side.SIDE_SELL : Side.SIDE_BUY;
  }

  static OrderType mapOrderType(Character value) {
    if (value == null) {
      return OrderType.ORDER_TYPE_UNSPECIFIED;
    }
    return switch (value) {
      case '1' -> OrderType.ORDER_TYPE_MARKET;
      case '2' -> OrderType.ORDER_TYPE_LIMIT;
      default -> OrderType.ORDER_TYPE_UNSPECIFIED;
    };
  }

  static TimeInForce mapTimeInForce(Character value) {
    if (value == null || value == '0') {
      return TimeInForce.TIME_IN_FORCE_ROD;
    }
    return switch (value) {
      case '3' -> TimeInForce.TIME_IN_FORCE_IOC;
      case '4' -> TimeInForce.TIME_IN_FORCE_FOK;
      default -> TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    };
  }

  static String optionalString(FieldMap fieldMap, int field) throws FieldNotFound {
    return optionalString(fieldMap, field, "");
  }

  static String optionalString(FieldMap fieldMap, int field, String fallback)
      throws FieldNotFound {
    return fieldMap.isSetField(field) ? fieldMap.getString(field) : fallback;
  }

  static Character optionalChar(FieldMap fieldMap, int field) throws FieldNotFound {
    return fieldMap.isSetField(field) ? fieldMap.getChar(field) : null;
  }
}
