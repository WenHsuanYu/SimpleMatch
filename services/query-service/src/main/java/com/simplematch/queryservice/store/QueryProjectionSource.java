package com.simplematch.queryservice.store;

/** Immutable Kafka provenance for one query projection fact. */
public record QueryProjectionSource(
    String topic, int partition, long offset, long observedAtUnixMs) {
  /** Validates the complete source identity before it reaches the durable inbox. */
  public QueryProjectionSource {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("query projection source topic is required");
    }
    if (partition < 0) {
      throw new IllegalArgumentException("query projection source partition must not be negative");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("query projection source offset must not be negative");
    }
    if (observedAtUnixMs < 0) {
      throw new IllegalArgumentException(
          "query projection observation timestamp must not be negative");
    }
  }

  QueryProjectionPosition position() {
    return new QueryProjectionPosition(partition, offset, observedAtUnixMs);
  }
}
