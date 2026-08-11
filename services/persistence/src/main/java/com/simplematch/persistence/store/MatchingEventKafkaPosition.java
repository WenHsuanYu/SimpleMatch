package com.simplematch.persistence.store;

/** Validated Kafka position and observed time for one final Matching Event. */
record MatchingEventKafkaPosition(int partition, long offset, long receivedAtUnixMs) {
  MatchingEventKafkaPosition {
    if (partition < 0 || partition > 14 || offset < 0 || receivedAtUnixMs < 0) {
      throw new IllegalArgumentException("matching event Kafka position is invalid");
    }
  }
}
