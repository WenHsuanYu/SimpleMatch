package com.simplematch.contracts.matching.runtime.v1;

import java.util.Arrays;
import java.util.Objects;

/** Validates transport identity shared by every {@code matching.events} Kafka consumer. */
public final class FinalMatchingEventTransportValidator {
  private FinalMatchingEventTransportValidator() {}

  /**
   * Requires the raw Kafka key and numeric partition to agree with one validated final event.
   *
   * @param recordKey raw Kafka record key
   * @param recordPartition numeric Kafka partition
   * @param envelope validated final Matching Event
   */
  public static void requireKafkaRecord(
      byte[] recordKey, int recordPartition, FinalMatchingEventEnvelope envelope) {
    final FinalMatchingEventEnvelope validated =
        Objects.requireNonNull(envelope, "envelope");
    if (recordKey == null || !Arrays.equals(validated.eventIdBytes(), recordKey)) {
      throw new IllegalArgumentException(
          "matching.events Kafka key must equal eventId bytes");
    }
    if (recordPartition != validated.event().getPartitionId()) {
      throw new IllegalArgumentException(
          "matching.events Kafka partition must equal partitionId");
    }
  }
}
