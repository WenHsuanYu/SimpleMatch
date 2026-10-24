package com.simplematch.queryservice.web;

import java.util.Optional;
import java.util.UUID;

/** Parses HTTP resource selectors without attaching identity, ownership or account authority. */
final class QueryResourceIds {
  private QueryResourceIds() {}

  static Optional<UUID> canonical(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      final UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value) ? Optional.of(parsed) : Optional.empty();
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }
}
