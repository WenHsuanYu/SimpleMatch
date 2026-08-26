package com.simplematch.tools.riskmatchinge2e;

import java.util.List;
import java.util.Objects;

/**
 * Provides bounded Kafka state observations for critical-consumer certification.
 *
 * <p>The interface deliberately hides Kafka client lifecycle, metadata discovery, consumer-group
 * lookup, and result normalization. Callers only request the two state snapshots required by the
 * certification protocol.
 */
public interface KafkaObservationSession extends AutoCloseable {
  int EXPECTED_PARTITION_COUNT = 15;

  /** Captures current log-end positions for matching.commands and matching.events. */
  LogEndPositions captureLogEndPositions();

  /** Captures durable matching.commands positions for all Matching consumer groups. */
  MatchingCommittedPositions captureMatchingCommittedPositions();

  /** Closes the underlying Kafka resources. */
  @Override
  void close();

  /** One topic partition and its current log-end offset. */
  record PartitionEndOffset(int partition, long offset) {
    public PartitionEndOffset {
      requirePartition(partition);
      if (offset < 0) {
        throw new IllegalArgumentException("offset must not be negative");
      }
    }
  }

  /** One topic partition and its durable consumer-group position. */
  record PartitionCommittedOffset(int partition, long committedOffset) {
    public PartitionCommittedOffset {
      requirePartition(partition);
      if (committedOffset < 0) {
        throw new IllegalArgumentException("committed offset must not be negative");
      }
    }
  }

  /** Complete log-end positions for one expected 15-partition topic. */
  record TopicEndPositions(String topic, List<PartitionEndOffset> partitions) {
    public TopicEndPositions {
      topic = requireText(topic, "topic");
      partitions = List.copyOf(Objects.requireNonNull(partitions, "partitions are required"));
      requireCompletePartitionSet(
          partitions.stream().map(PartitionEndOffset::partition).toList(), topic);
    }
  }

  /** Log-end positions for both Kafka journals used by the observation window. */
  record LogEndPositions(TopicEndPositions matchingCommands, TopicEndPositions matchingEvents) {
    public LogEndPositions {
      Objects.requireNonNull(matchingCommands, "matching.commands positions are required");
      Objects.requireNonNull(matchingEvents, "matching.events positions are required");
    }
  }

  /** Durable Matching positions for matching.commands. */
  record MatchingCommittedPositions(
      String topic, List<PartitionCommittedOffset> partitions) {
    public MatchingCommittedPositions {
      topic = requireText(topic, "topic");
      partitions = List.copyOf(Objects.requireNonNull(partitions, "partitions are required"));
      requireCompletePartitionSet(
          partitions.stream().map(PartitionCommittedOffset::partition).toList(), topic);
    }
  }

  private static void requirePartition(int partition) {
    if (partition < 0 || partition >= EXPECTED_PARTITION_COUNT) {
      throw new IllegalArgumentException(
          "partition must be between 0 and " + (EXPECTED_PARTITION_COUNT - 1));
    }
  }

  private static void requireCompletePartitionSet(List<Integer> partitions, String topic) {
    if (partitions.size() != EXPECTED_PARTITION_COUNT) {
      throw new IllegalArgumentException(
          topic + " must contain exactly " + EXPECTED_PARTITION_COUNT + " partitions");
    }
    for (int partition = 0; partition < EXPECTED_PARTITION_COUNT; partition++) {
      if (partitions.get(partition) != partition) {
        throw new IllegalArgumentException(
            topic + " partitions must be ordered and cover 0 through "
                + (EXPECTED_PARTITION_COUNT - 1));
      }
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
