package com.simplematch.quickfixgateway.wal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Strict reader for the field types and names in the v1 WAL JSON document. */
final class WalJsonDocument {
  private static final Set<String> V1_FIELDS =
      Set.of(
          "schemaVersion",
          "recordId",
          "createdAtUnixMs",
          "sourceService",
          "senderCompId",
          "targetCompId",
          "messageType",
          "orderId",
          "clOrdId",
          "origClOrdId",
          "accountId",
          "symbol",
          "side",
          "quantity",
          "price",
          "orderType",
          "tif",
          "commandType",
          "rawFix");

  private final JsonNode json;

  WalJsonDocument(JsonNode json) {
    if (json == null || !json.isObject()) {
      throw new WalRecordCodecException("WAL line must be a JSON object");
    }
    this.json = json;
    rejectUnknownFields();
  }

  String requiredText(String fieldName) {
    final JsonNode value = json.get(fieldName);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new WalRecordCodecException(fieldName + " must be a nonblank JSON string");
    }
    return value.textValue();
  }

  String optionalText(String fieldName) {
    final JsonNode value = json.get(fieldName);
    if (value == null || !value.isTextual()) {
      throw new WalRecordCodecException(fieldName + " must be a JSON string");
    }
    return value.textValue();
  }

  long requiredLong(String fieldName) {
    final JsonNode value = json.get(fieldName);
    if (value == null || !value.isIntegralNumber()) {
      throw new WalRecordCodecException(fieldName + " must be an integer JSON number");
    }
    return value.longValue();
  }

  <E extends Enum<E>> E enumValue(String fieldName, Class<E> enumType) {
    final String value = requiredText(fieldName);
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException exception) {
      throw new WalRecordCodecException(
          fieldName + " has unsupported value " + value, exception);
    }
  }

  void requirePlaceholder(String fieldName, String expected) {
    if (!expected.equals(optionalText(fieldName))) {
      throw new WalRecordCodecException(
          "WAL field " + fieldName + " must use the v1 placeholder " + expected);
    }
  }

  private void rejectUnknownFields() {
    final Iterator<String> names = json.fieldNames();
    final Set<String> unknown = new HashSet<>();
    while (names.hasNext()) {
      final String name = names.next();
      if (!V1_FIELDS.contains(name)) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw new WalRecordCodecException("unsupported WAL fields: " + unknown);
    }
  }
}
