package com.simplematch.marketdatapublisher.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Reads primitive fields from the unchanged flat market snapshot JSON contract. */
final class MarketSnapshotJsonReader {
  private MarketSnapshotJsonReader() {}

  static JsonNode parse(ObjectMapper objectMapper, byte[] sourceBytes) {
    try {
      return objectMapper.readTree(sourceBytes);
    } catch (IOException exception) {
      throw new MarketSnapshotValidationException(
          "market snapshot source must be valid JSON", exception);
    }
  }

  static List<JsonNode> nodes(JsonNode value, String fieldName) {
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new MarketSnapshotValidationException(fieldName + " must be an array");
    }
    final List<JsonNode> nodes = new ArrayList<>();
    value.forEach(nodes::add);
    return List.copyOf(nodes);
  }

  static List<String> textList(JsonNode value, String fieldName) {
    return nodes(value, fieldName).stream().map(item -> textItem(item, fieldName)).toList();
  }

  static String textValue(JsonNode object, String fieldName) {
    final JsonNode value = object.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new MarketSnapshotValidationException(fieldName + " must be a string");
    }
    return value.textValue();
  }

  static int intValue(JsonNode object, String fieldName) {
    final JsonNode value = object.get(fieldName);
    if (value == null || value.isNull()) {
      return 0;
    }
    if (!value.canConvertToInt() || !value.isIntegralNumber()) {
      throw new MarketSnapshotValidationException(fieldName + " must be an integer");
    }
    return value.intValue();
  }

  static long longValue(JsonNode object, String fieldName) {
    final JsonNode value = object.get(fieldName);
    if (value == null || value.isNull()) {
      return 0L;
    }
    if (!value.canConvertToLong() || !value.isIntegralNumber()) {
      throw new MarketSnapshotValidationException(fieldName + " must be an integer");
    }
    return value.longValue();
  }

  static void requireObject(JsonNode value, String fieldName) {
    if (value == null || value.isNull() || !value.isObject()) {
      throw new MarketSnapshotValidationException(fieldName + " must be an object");
    }
  }

  static void rejectUnknownFields(JsonNode object, Set<String> allowedFields, String fieldName) {
    final Iterator<String> fields = object.fieldNames();
    while (fields.hasNext()) {
      final String field = fields.next();
      if (!allowedFields.contains(field)) {
        throw new MarketSnapshotValidationException(
            fieldName + " contains unknown field: " + field);
      }
    }
  }

  private static String textItem(JsonNode value, String fieldName) {
    if (value == null || value.isNull() || !value.isTextual()) {
      throw new MarketSnapshotValidationException(fieldName + " must contain strings");
    }
    return value.textValue();
  }
}
