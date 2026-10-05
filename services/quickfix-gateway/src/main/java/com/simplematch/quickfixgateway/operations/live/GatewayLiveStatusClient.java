package com.simplematch.quickfixgateway.operations.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.quickfixgateway.config.GatewayLiveObservationProperties;
import com.simplematch.quickfixgateway.operations.ConsumerPartitionProgress;
import com.simplematch.quickfixgateway.operations.CriticalConsumer;
import com.simplematch.quickfixgateway.operations.CriticalConsumerStatus;
import com.simplematch.quickfixgateway.operations.MatchingFleetStatus;
import com.simplematch.quickfixgateway.operations.MatchingPartitionStatus;
import com.simplematch.quickfixgateway.operations.OperationalComponentState;
import com.simplematch.quickfixgateway.operations.RiskStatus;
import com.simplematch.quickfixgateway.operations.TradingIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Normalizes internal Risk, Matching, and consumer HTTP documents into Gateway domain facts. */
public final class GatewayLiveStatusClient {
  private static final int PARTITION_COUNT = 15;

  private final StatusDocumentClient documents;
  private final GatewayLiveObservationProperties.Endpoints endpoints;
  private final Supplier<CriticalConsumerOperationalStatus> quickfixStatus;
  private final Executor executor;

  /** Creates the HTTP normalization adapter for the fixed Phase 1 topology. */
  public GatewayLiveStatusClient(
      StatusDocumentClient documents,
      GatewayLiveObservationProperties.Endpoints endpoints,
      Supplier<CriticalConsumerOperationalStatus> quickfixStatus,
      Executor executor) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.quickfixStatus = Objects.requireNonNull(quickfixStatus, "quickfixStatus");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /** Reads Risk availability and the canonical daily trading identity. */
  public RiskStatus riskStatus() {
    return StatusDocumentDecoder.riskStatus(documents.read(endpoints.risk()));
  }

  /** Reads exactly one owner and its recovery/progress facts for all 15 Matching partitions. */
  public MatchingFleetStatus matchingFleet(Map<Integer, Long> commandEndOffsets) {
    final List<CompletableFuture<MatchingPartitionStatus>> pending =
        new ArrayList<>(PARTITION_COUNT);
    for (int partition = 0; partition < PARTITION_COUNT; partition++) {
      final int expectedPartition = partition;
      pending.add(
          CompletableFuture.supplyAsync(
              () -> {
                final JsonNode root =
                    documents.read(endpoints.matchingTemplate().formatted(expectedPartition));
                return StatusDocumentDecoder.matchingPartition(
                    root,
                    expectedPartition,
                    requiredOffset(commandEndOffsets, expectedPartition));
              },
              executor));
    }
    final List<MatchingPartitionStatus> partitions =
        pending.stream().map(GatewayLiveStatusClient::join).toList();
    Instant oldestObservation = null;
    for (MatchingPartitionStatus status : partitions) {
      oldestObservation =
          oldestObservation == null || status.observedAt().isBefore(oldestObservation)
              ? status.observedAt()
              : oldestObservation;
    }
    return new MatchingFleetStatus(partitions, Objects.requireNonNull(oldestObservation));
  }

  /** Reads quarantine/age facts and combines them with Kafka-authoritative group progress. */
  public List<CriticalConsumerStatus> criticalConsumers(
      TradingIdentity identity, KafkaAdmissionSnapshot kafka) {
    final List<CriticalConsumerStatus> statuses = new ArrayList<>(CriticalConsumer.values().length);
    final CompletableFuture<CriticalConsumerStatus> persistencePending =
        criticalConsumerAsync(
            CriticalConsumer.PERSISTENCE, endpoints.persistence(), identity, kafka);
    final CompletableFuture<CriticalConsumerStatus> accountPending =
        criticalConsumerAsync(CriticalConsumer.ACCOUNT, endpoints.account(), identity, kafka);
    statuses.add(join(persistencePending));
    statuses.add(join(accountPending));
    statuses.add(
        criticalConsumer(
            CriticalConsumer.QUICKFIX,
            quickfixStatus.get(),
            identity,
            kafka.consumerCommittedOffsets().get(CriticalConsumer.QUICKFIX),
            kafka.eventEndOffsets(),
            kafka.observedAt()));
    return List.copyOf(statuses);
  }

  private CompletableFuture<CriticalConsumerStatus> criticalConsumerAsync(
      CriticalConsumer component,
      String endpoint,
      TradingIdentity identity,
      KafkaAdmissionSnapshot kafka) {
    return CompletableFuture.supplyAsync(
        () ->
            criticalConsumer(
                component,
                StatusDocumentDecoder.consumerObservation(documents.read(endpoint)),
                identity,
                kafka.consumerCommittedOffsets().get(component),
                kafka.eventEndOffsets(),
                kafka.observedAt()),
        executor);
  }

  private CriticalConsumerStatus criticalConsumer(
      CriticalConsumer component,
      CriticalConsumerOperationalStatus source,
      TradingIdentity identity,
      Map<Integer, Long> kafkaCommittedOffsets,
      Map<Integer, Long> eventEndOffsets,
      Instant kafkaObservedAt) {
    if (kafkaCommittedOffsets == null) {
      throw new IllegalStateException("Kafka consumer group progress is missing: " + component);
    }
    final Map<Integer, Long> ages = source.oldestUnprocessedAgeMillis();
    final List<ConsumerPartitionProgress> progress = new ArrayList<>(PARTITION_COUNT);
    for (int partition = 0; partition < PARTITION_COUNT; partition++) {
      final long endOffset = requiredOffset(eventEndOffsets, partition);
      final long kafkaOffset = observedOffset(kafkaCommittedOffsets, partition, endOffset);
      progress.add(
          new ConsumerPartitionProgress(
              partition,
              kafkaOffset,
              endOffset,
              Optional.ofNullable(ages.get(partition)).map(Duration::ofMillis)));
    }
    final boolean quarantined = source.quarantined();
    final Instant serviceObservedAt = source.observedAt();
    final Instant observedAt =
        serviceObservedAt.isBefore(kafkaObservedAt) ? serviceObservedAt : kafkaObservedAt;
    return new CriticalConsumerStatus(
        component,
        quarantined ? OperationalComponentState.QUARANTINED : OperationalComponentState.READY,
        identity,
        progress,
        observedAt,
        quarantined ? "QUARANTINED" : "READY");
  }

  private static long observedOffset(Map<Integer, Long> offsets, int partition, long endOffset) {
    final Long offset = offsets.get(partition);
    if (offset == null) {
      if (endOffset == 0) {
        return 0;
      }
      throw new IllegalStateException("consumer progress is incomplete for partition " + partition);
    }
    if (offset < 0) {
      throw new IllegalStateException("consumer offset is negative for partition " + partition);
    }
    return offset;
  }

  private static long requiredOffset(Map<Integer, Long> offsets, int partition) {
    final Long offset = offsets.get(partition);
    if (offset == null || offset < 0) {
      throw new IllegalStateException("Kafka end offset is missing for partition " + partition);
    }
    return offset;
  }

  private static <T> T join(CompletableFuture<T> pending) {
    try {
      return pending.join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw failure;
    }
  }
}
