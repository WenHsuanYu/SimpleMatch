package com.simplematch.tools.riskmatchinge2e;

import java.util.Objects;

/** Immutable details for one physical Kafka record matching the run command identity. */
record ObservedRecord(
    int partition,
    long offset,
    long timestamp,
    String key,
    Bytes value) {

  /** Requires the record key and payload value. */
  ObservedRecord {
    Objects.requireNonNull(key, "record key is required");
    Objects.requireNonNull(value, "record value is required");
  }
}