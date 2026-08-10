package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;

/** Strict primitive JSON access shared by all official-source parsers. */
final class OfficialJsonRows {
  private final ObjectMapper objectMapper;

  OfficialJsonRows(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper is required");
  }

  List<JsonNode> array(RetrievedOfficialSource source, String label) {
    try {
      final JsonNode root = objectMapper.readTree(source.content());
      if (root == null || !root.isArray() || root.isEmpty()) {
        throw new MarketReferenceBuildException(label + " must be a non-empty JSON array");
      }
      return StreamSupport.stream(root.spliterator(), false).toList();
    } catch (IOException exception) {
      throw new MarketReferenceBuildException(label + " must be valid JSON", exception);
    }
  }

  String requiredText(JsonNode row, String fieldName, String label) {
    if (row == null || !row.isObject()) {
      throw new MarketReferenceBuildException(label + " contains a non-object row");
    }
    final JsonNode value = row.get(fieldName);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new MarketReferenceBuildException(label + " is missing textual field: " + fieldName);
    }
    return value.textValue().trim();
  }

  String optionalText(JsonNode row, String fieldName) {
    final JsonNode value = row.get(fieldName);
    return value != null && value.isTextual() ? value.textValue() : "";
  }
}
