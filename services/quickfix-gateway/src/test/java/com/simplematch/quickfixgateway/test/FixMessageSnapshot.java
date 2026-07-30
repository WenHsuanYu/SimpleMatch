package com.simplematch.quickfixgateway.test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.MsgType;
import quickfix.field.TransactTime;

public final class FixMessageSnapshot {
  private static final DateTimeFormatter FIX_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");

  private FixMessageSnapshot() {}

  public static String snapshot(Message message, int... tags) {
    return Arrays.stream(tags)
        .mapToObj(tag -> tag + "=" + normalize(tag, value(message, tag)))
        .collect(Collectors.joining("|"));
  }

  private static String value(Message message, int tag) {
    try {
      if (tag == MsgType.FIELD) {
        return message.getHeader().getString(tag);
      }
      return message.getString(tag);
    } catch (FieldNotFound exception) {
      throw new AssertionError("missing FIX field " + tag, exception);
    }
  }

  private static String normalize(int tag, String value) {
    if (tag == TransactTime.FIELD) {
      return LocalDateTime.parse(value, FIX_TIMESTAMP).toInstant(ZoneOffset.UTC).toString();
    }
    if (tag == 6 || tag == 14 || tag == 31 || tag == 32 || tag == 151) {
      return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
    return value;
  }
}
