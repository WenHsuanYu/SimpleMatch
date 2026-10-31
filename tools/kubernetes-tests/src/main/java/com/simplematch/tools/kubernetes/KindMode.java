package com.simplematch.tools.kubernetes;

/** Owns the two current cluster naming and deletion policies. */
enum KindMode {
  DEDICATED,
  DISPOSABLE;

  static KindMode parse(String value) {
    return switch (value) {
      case "dedicated" -> DEDICATED;
      case "disposable" -> DISPOSABLE;
      default -> throw new IllegalArgumentException(
          "kubernetesMode must be dedicated or disposable");
    };
  }

  static String namespace(String runId) {
    return "simplematch-s3-" + runId.substring(0, 12);
  }

  String cluster(String runId) {
    return this == DEDICATED ? "simplematch-live" : namespace(runId);
  }

  boolean mayDelete(boolean created, boolean prepared) {
    return created && (this == DISPOSABLE || !prepared);
  }

  String value() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
