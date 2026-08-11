package com.simplematch.quickfixgateway.store;

/** Validated Kafka position and observed time for the Gateway's final-event consumer. */
record FinalFixDeliveryKafkaPosition(int partition, long offset, long observedAtUnixMs) {
  FinalFixDeliveryKafkaPosition {
    if (partition < 0 || partition > 14 || offset < 0 || observedAtUnixMs < 0) {
      throw new IllegalArgumentException("final Matching Event Kafka position is invalid");
    }
  }
}
