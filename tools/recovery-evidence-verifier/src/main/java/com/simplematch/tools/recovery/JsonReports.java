package com.simplematch.tools.recovery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;

/** Reads exactly one object and rejects contradictory duplicate fields. */
final class JsonReports {
  private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private JsonReports() {}

  static JsonNode read(Path path) throws IOException {
    final JsonNode value = MAPPER.readTree(path.toFile());
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Expected one JSON object: " + path);
    }
    return value;
  }

  static JsonNode parse(String text) throws IOException {
    final JsonNode value = MAPPER.readTree(text);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Expected one JSON object.");
    }
    return value;
  }

  static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  static void write(Path path, JsonNode value) throws IOException {
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
  }
}
