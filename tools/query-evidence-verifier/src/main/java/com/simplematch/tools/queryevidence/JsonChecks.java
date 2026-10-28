package com.simplematch.tools.queryevidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/** JSON predicates that reject missing values and preserve numeric equality across encodings. */
final class JsonChecks {
  private JsonChecks() {}

  static boolean text(JsonNode value, String expected) {
    return value.isTextual() && expected.equals(value.textValue());
  }

  static boolean nonemptyText(JsonNode value) {
    return value.isTextual() && !value.textValue().isEmpty();
  }

  static boolean number(JsonNode value, Predicate<BigDecimal> condition) {
    return value.isNumber() && condition.test(value.decimalValue());
  }

  static boolean number(JsonNode value, long expected) {
    return number(value, actual -> actual.compareTo(BigDecimal.valueOf(expected)) == 0);
  }

  /** Checks literal facts at JSON pointers without coercing strings, numbers, or booleans. */
  static boolean values(JsonNode evidence, Map<String, ?> expected) {
    return expected.entrySet().stream()
        .allMatch(
            entry -> {
              final JsonNode actual = evidence.at(entry.getKey());
              return switch (entry.getValue()) {
                case String value -> text(actual, value);
                case Integer value -> number(actual, value.longValue());
                case Boolean value -> actual.isBoolean() && actual.booleanValue() == value;
                default -> false;
              };
            });
  }

  static boolean equal(JsonNode first, JsonNode second) {
    return !first.isMissingNode()
        && !second.isMissingNode()
        && first.equals(
            (left, right) -> {
              if (left.isNumber() && right.isNumber()) {
                return left.decimalValue().compareTo(right.decimalValue());
              }
              return left.equals(right) ? 0 : 1;
            },
            second);
  }

  static List<JsonNode> elements(JsonNode array) {
    return array.isArray() ? StreamSupport.stream(array.spliterator(), false).toList() : List.of();
  }

  static boolean fields(JsonNode object, Set<String> expected) {
    final Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    return object.isObject() && actual.equals(expected);
  }
}
