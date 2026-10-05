package com.simplematch.quickfixgateway.operations.live;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Strict typed reads shared by the internal status document decoders. */
final class StatusDocumentFields {
  private StatusDocumentFields() {}

  static Map<Integer, Long> longMap(JsonNode root, String field) {
    final JsonNode value = requiredObject(root, field);
    final Map<Integer, Long> result = new HashMap<>();
    value.properties()
        .forEach(
            entry -> {
              if (!entry.getValue().canConvertToLong()) {
                throw new IllegalStateException(field + " contains a non-integer value");
              }
              result.put(Integer.parseInt(entry.getKey()), entry.getValue().longValue());
            });
    return Map.copyOf(result);
  }

  static JsonNode requiredObject(JsonNode root, String field) {
    final JsonNode value = root.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalStateException("required object is missing: " + field);
    }
    return value;
  }

  static String requiredText(JsonNode root, String field) {
    final JsonNode value = root.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalStateException("required text is missing: " + field);
    }
    return value.textValue();
  }

  static boolean requiredBoolean(JsonNode root, String field) {
    final JsonNode value = root.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalStateException("required boolean is missing: " + field);
    }
    return value.booleanValue();
  }

  static int requiredInt(JsonNode root, String field) {
    final JsonNode value = root.get(field);
    if (value == null || !value.canConvertToInt()) {
      throw new IllegalStateException("required integer is missing: " + field);
    }
    return value.intValue();
  }

  static long requiredLong(JsonNode root, String field) {
    final JsonNode value = root.get(field);
    if (value == null || !value.canConvertToLong()) {
      throw new IllegalStateException("required long is missing: " + field);
    }
    return value.longValue();
  }

  static Instant requiredInstant(JsonNode root, String field) {
    try {
      return Instant.parse(requiredText(root, field));
    } catch (RuntimeException failure) {
      throw new IllegalStateException("required timestamp is invalid: " + field, failure);
    }
  }
}
