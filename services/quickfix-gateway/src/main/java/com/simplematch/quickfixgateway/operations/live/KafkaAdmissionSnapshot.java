package com.simplematch.quickfixgateway.operations.live;

import com.simplematch.quickfixgateway.operations.CriticalConsumer;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * One Kafka control-plane sample of topology and durable Matching/critical-consumer progress.
 * Matching offsets are acknowledged command-group positions, not native commit candidates.
 */
public record KafkaAdmissionSnapshot(
    int commandPartitionCount,
    int eventPartitionCount,
    Map<Integer, Long> commandEndOffsets,
    Map<Integer, Long> matchingCommittedOffsets,
    Map<Integer, Long> eventEndOffsets,
    Map<CriticalConsumer, Map<Integer, Long>> consumerCommittedOffsets,
    Instant observedAt) {
  /** Defensively owns all topology and progress maps. */
  public KafkaAdmissionSnapshot {
    if (commandPartitionCount <= 0 || eventPartitionCount <= 0) {
      throw new IllegalArgumentException("Kafka partition counts must be positive");
    }
    commandEndOffsets = Map.copyOf(commandEndOffsets);
    matchingCommittedOffsets = Map.copyOf(matchingCommittedOffsets);
    eventEndOffsets = Map.copyOf(eventEndOffsets);
    final EnumMap<CriticalConsumer, Map<Integer, Long>> copied =
        new EnumMap<>(CriticalConsumer.class);
    consumerCommittedOffsets.forEach(
        (consumer, offsets) -> copied.put(consumer, Map.copyOf(offsets)));
    consumerCommittedOffsets = Map.copyOf(copied);
    Objects.requireNonNull(observedAt, "observedAt");
  }
}
