package com.simplematch.tools.riskmatchinge2e;

import java.util.List;
import java.util.Objects;

/**
 * Provides bounded Kafka state observations for critical-consumer certification.
 *
 * <p>The interface deliberately hides Kafka client lifecycle, metadata discovery, consumer-group
 * lookup, and result normalization. Callers request bounded state snapshots and exact payload
 * evidence required by the certification protocol.
 */
public interface KafkaObservationSession extends AutoCloseable {
  int EXPECTED_PARTITION_COUNT = 15;

  /** Captures current log-end positions for matching.commands and matching.events. */
  LogEndPositions captureLogEndPositions();

  /** Captures durable matching.commands positions for all Matching consumer groups. */
  MatchingCommittedPositions captureMatchingCommittedPositions();

  /** Verifies the exact Close Barrier payload inside each frozen command offset range. */
  CloseBarrierEvidence verifyCloseBarriers(CloseBarrierExpectation expectation);

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

  /** Expected session identity and immutable observation boundaries for Close Barriers. */
  record CloseBarrierExpectation(
      String tradingSessionId,
      String tradingDay,
      String artifactContentSha256,
      String routingAlgorithmVersion,
      TopicEndPositions before,
      TopicEndPositions after) {
    public CloseBarrierExpectation {
      tradingSessionId = requireText(tradingSessionId, "trading session id");
      tradingDay = requireText(tradingDay, "trading day");
      artifactContentSha256 =
          requireText(artifactContentSha256, "artifact content sha256");
      routingAlgorithmVersion =
          requireText(routingAlgorithmVersion, "routing algorithm version");
      Objects.requireNonNull(before, "baseline positions are required");
      Objects.requireNonNull(after, "terminal positions are required");
      if (!before.topic().equals(after.topic())) {
        throw new IllegalArgumentException("offset boundaries must use the same topic");
      }
      for (int partition = 0; partition < EXPECTED_PARTITION_COUNT; partition++) {
        final long baseline = before.partitions().get(partition).offset();
        final long terminal = after.partitions().get(partition).offset();
        if (terminal != baseline + 1) {
          throw new IllegalArgumentException(
              "each Close Barrier range must contain exactly one record");
        }
      }
    }
  }

  /** Decoded physical Close Barrier identities retained as certification evidence. */
  record CloseBarrierEvidence(String topic, List<CloseBarrierRecord> records) {
    public CloseBarrierEvidence {
      topic = requireText(topic, "topic");
      records = List.copyOf(Objects.requireNonNull(records, "records are required"));
      requireCompletePartitionSet(
          records.stream().map(CloseBarrierRecord::partition).toList(), topic);
    }
  }

  /** One decoded Close Barrier and its physical Kafka location. */
  record CloseBarrierRecord(int partition, long offset, String commandId) {
    public CloseBarrierRecord {
      requirePartition(partition);
      if (offset < 0) {
        throw new IllegalArgumentException("offset must not be negative");
      }
      commandId = requireText(commandId, "command id");
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
