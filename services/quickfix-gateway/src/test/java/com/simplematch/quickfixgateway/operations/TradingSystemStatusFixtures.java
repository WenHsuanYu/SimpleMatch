package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Creates complete, internally consistent Phase 1 operational status observations for tests. */
final class TradingSystemStatusFixtures {
  private static final int PARTITION_COUNT = 15;

  private TradingSystemStatusFixtures() {}

  static TradingSystemObservation readyObservation(Instant observedAt) {
    final TradingIdentity identity = identity();
    final List<MatchingPartitionStatus> matchingPartitions =
        IntStream.range(0, PARTITION_COUNT)
            .mapToObj(
                partitionId ->
                    new MatchingPartitionStatus(
                        partitionId,
                        "matching-" + partitionId,
                        OperationalComponentState.READY,
                        identity,
                        true,
                        true,
                        500,
                        500,
                        observedAt,
                        "READY"))
            .toList();
    final List<CriticalConsumerStatus> consumers =
        List.of(
            consumer(CriticalConsumer.PERSISTENCE, identity, observedAt),
            consumer(CriticalConsumer.ACCOUNT, identity, observedAt),
            consumer(CriticalConsumer.QUICKFIX, identity, observedAt));
    return new TradingSystemObservation(
        new RiskStatus(OperationalComponentState.READY, identity, observedAt, "READY"),
        new MatchingFleetStatus(matchingPartitions, observedAt),
        consumers,
        new KafkaStatus(
            OperationalComponentState.READY,
            identity,
            PARTITION_COUNT,
            PARTITION_COUNT,
            false,
            observedAt,
            "READY"));
  }

  static TradingSystemObservation withMatchingState(
      TradingSystemObservation source, int partitionId, OperationalComponentState state) {
    final List<MatchingPartitionStatus> partitions =
        new ArrayList<>(source.matchingFleet().partitions());
    final MatchingPartitionStatus current = partitions.get(partitionId);
    partitions.set(
        partitionId,
        new MatchingPartitionStatus(
            current.partitionId(),
            current.ownerId(),
            state,
            current.identity(),
            current.ownershipPermit(),
            current.recoveryComplete(),
            current.committedOffset(),
            current.endOffset(),
            current.observedAt(),
            "TEST_OVERRIDE"));
    return copyWithMatching(source, partitions);
  }

  static TradingSystemObservation withMatchingIdentity(
      TradingSystemObservation source, int partitionId, TradingIdentity identity) {
    final List<MatchingPartitionStatus> partitions =
        new ArrayList<>(source.matchingFleet().partitions());
    final MatchingPartitionStatus current = partitions.get(partitionId);
    partitions.set(
        partitionId,
        new MatchingPartitionStatus(
            current.partitionId(),
            current.ownerId(),
            current.state(),
            identity,
            current.ownershipPermit(),
            current.recoveryComplete(),
            current.committedOffset(),
            current.endOffset(),
            current.observedAt(),
            "TEST_OVERRIDE"));
    return copyWithMatching(source, partitions);
  }

  static TradingSystemObservation withCriticalOldestAge(
      TradingSystemObservation source,
      CriticalConsumer component,
      int partitionId,
      Duration oldestUnprocessedAge) {
    final List<CriticalConsumerStatus> consumers = new ArrayList<>(source.criticalConsumers());
    final int index = consumers.indexOf(
        consumers.stream().filter(status -> status.component() == component).findFirst().orElseThrow());
    final CriticalConsumerStatus current = consumers.get(index);
    final List<ConsumerPartitionProgress> progress = new ArrayList<>(current.partitionProgress());
    final ConsumerPartitionProgress existing = progress.get(partitionId);
    progress.set(
        partitionId,
        new ConsumerPartitionProgress(
            existing.partitionId(),
            existing.committedOffset(),
            existing.endOffset() + 1,
            Optional.of(oldestUnprocessedAge)));
    consumers.set(
        index,
        new CriticalConsumerStatus(
            current.component(),
            current.state(),
            current.identity(),
            progress,
            current.observedAt(),
            current.reason()));
    return new TradingSystemObservation(
        source.riskStatus(), source.matchingFleet(), consumers, source.kafkaStatus());
  }

  static TradingSystemObservation withKafkaEventIdentityConflict(TradingSystemObservation source) {
    final KafkaStatus kafka = source.kafkaStatus();
    return new TradingSystemObservation(
        source.riskStatus(),
        source.matchingFleet(),
        source.criticalConsumers(),
        new KafkaStatus(
            kafka.state(),
            kafka.identity(),
            kafka.commandPartitionCount(),
            kafka.eventPartitionCount(),
            true,
            kafka.observedAt(),
            "EVENT_ID_PAYLOAD_CONFLICT"));
  }

  static TradingIdentity identity() {
    return new TradingIdentity(
        "2026-08-11-XTAI",
        "market-reference-2026-08-11",
        "e4a1bdc9",
        1,
        1,
        "lmax-matching-v1",
        "sha256:matching-image-20260811");
  }

  static TradingIdentity differentArtifactIdentity() {
    return new TradingIdentity(
        "2026-08-11-XTAI",
        "market-reference-2026-08-11-revised",
        "c0ffee12",
        1,
        1,
        "lmax-matching-v1",
        "sha256:matching-image-20260811");
  }

  private static CriticalConsumerStatus consumer(
      CriticalConsumer component, TradingIdentity identity, Instant observedAt) {
    final List<ConsumerPartitionProgress> progress =
        IntStream.range(0, PARTITION_COUNT)
            .mapToObj(partitionId -> new ConsumerPartitionProgress(partitionId, 500, 500, Optional.empty()))
            .toList();
    return new CriticalConsumerStatus(
        component, OperationalComponentState.READY, identity, progress, observedAt, "READY");
  }

  private static TradingSystemObservation copyWithMatching(
      TradingSystemObservation source, List<MatchingPartitionStatus> partitions) {
    return new TradingSystemObservation(
        source.riskStatus(),
        new MatchingFleetStatus(partitions, source.matchingFleet().observedAt()),
        source.criticalConsumers(),
        source.kafkaStatus());
  }
}
