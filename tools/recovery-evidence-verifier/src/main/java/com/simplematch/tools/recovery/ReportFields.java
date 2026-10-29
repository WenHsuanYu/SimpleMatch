package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;

/** Typed access to required fields shared by the three dependency predicates. */
final class ReportFields {
  private ReportFields() {}

  static JsonNode object(JsonNode node, String field) {
    final JsonNode value = node.path(field);
    require(value.isObject(), "Missing object: " + field);
    return value;
  }

  static String text(JsonNode node, String field) {
    final JsonNode value = node.path(field);
    require(value.isTextual() && !value.textValue().isBlank(), "Missing text: " + field);
    return value.textValue();
  }

  static void flag(JsonNode node, String field, boolean expected) {
    final JsonNode value = node.path(field);
    require(value.isBoolean() && value.booleanValue() == expected, "Invalid flag: " + field);
  }

  static int integer(JsonNode node, String field) {
    final JsonNode value = node.path(field);
    require(value.isInt() && value.intValue() >= 0, "Invalid integer: " + field);
    return value.intValue();
  }

  static long longInteger(JsonNode node, String field) {
    final JsonNode value = node.path(field);
    require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0,
        "Invalid integer: " + field);
    return value.longValue();
  }

  static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
