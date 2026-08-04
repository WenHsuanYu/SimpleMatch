package com.simplematch.config;

/** Normalizes shared capability-property scalar values consistently. */
final class PlatformPropertyDefaults {
  private PlatformPropertyDefaults() {}

  /** Returns a non-blank configured value or its local-development fallback. */
  static String string(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Returns a configured integer or its fallback when the property is absent. */
  static Integer integerOrDefault(Integer value, int fallback) {
    return value == null ? Integer.valueOf(fallback) : value;
  }
}
