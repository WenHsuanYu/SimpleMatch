package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.regex.Pattern;

/** Redacts credentials before captured command diagnostics leave the process adapter. */
final class DiagnosticText {
  private static final Pattern URI_USER = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
  private static final String PRIVATE_NAMES = "(password|passwd|authorization|"
          + "token|access_token|refresh_token|client_secret|"
          + "client-key-data|client-certificate-data)";
  private static final Pattern PRIVATE_NAME = Pattern.compile("(?i)" + PRIVATE_NAMES);
  private static final Pattern PRIVATE_FIELD = Pattern.compile(
      "(?i)" + PRIVATE_NAMES
          + "[\"']?\\s*[:=]\\s*(?:Bearer\\s+)?"
          + "(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;]+)");

  private DiagnosticText() {}

  static String failure(Throwable failure) {
    if (failure == null) {
      return "";
    }
    final String safe = redact(failure.getClass().getName() + ": " + failure.getMessage());
    return safe.length() <= 4_000 ? safe : safe.substring(0, 1_000)
        + "\n[intermediate diagnostic omitted]\n" + safe.substring(safe.length() - 3_000);
  }

  static String redact(String text) {
    final String withoutUser = URI_USER.matcher(text).replaceAll("$1<REDACTED>@");
    return PRIVATE_FIELD.matcher(withoutUser).replaceAll("$1=<REDACTED>");
  }

  /** Preserves diagnostic JSON structure, including Boolean token-mount flags. */
  static JsonNode redact(JsonNode document) {
    return redact(document, false);
  }

  private static JsonNode redact(JsonNode value, boolean sensitive) {
    if (value.isObject()) {
      final var safe = JsonNodeFactory.instance.objectNode();
      final boolean privateEnvironment = PRIVATE_NAME.matcher(value.path("name").asText()).find();
      for (var field : value.properties()) {
        final boolean hidden = sensitive || PRIVATE_NAME.matcher(field.getKey()).find()
            || (privateEnvironment && "value".equals(field.getKey()));
        safe.set(field.getKey(), redact(field.getValue(), hidden));
      }
      return safe;
    }
    if (value.isArray()) {
      final var safe = JsonNodeFactory.instance.arrayNode();
      value.forEach(child -> safe.add(redact(child, sensitive)));
      return safe;
    }
    return value.isTextual()
        ? TextNode.valueOf(sensitive ? "<REDACTED>" : redact(value.textValue())) : value;
  }
}
