package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheEntry;
import com.simplematch.marketdataprojection.kafka.MarketDataOutboxRecord;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionResult;
import java.util.List;

/**
 * Durable storage seam for one final Matching Event and its complete public projection snapshot.
 */
public interface MarketDataProjectionStore {
  /** Applies exactly one ordered event inside the caller-owned local transaction. */
  MarketDataProjectionResult project(
      FinalMatchingEventEnvelope envelope,
      int kafkaPartition,
      long kafkaOffset,
      long observedAtUnixMs);

  /** Durably records that a later replay must start from a clean checkpoint for one partition. */
  void markResyncRequired(int kafkaPartition, long failedKafkaOffset, long observedAtUnixMs);

  /** Returns unpublished complete snapshots in deterministic creation order for Kafka dispatch. */
  List<MarketDataOutboxRecord> pendingOutbox(int limit);

  /** Marks one producer-acknowledged snapshot event as published without changing its payload. */
  void markOutboxPublished(MarketDataOutboxRecord record, long publishedAtUnixMs);

  /** Returns database-backed snapshots that still need their independent Redis cache copy. */
  List<MarketDataSnapshotCacheEntry> pendingRedisSnapshots(int limit);

  /** Marks one unchanged snapshot as cached only after the Redis adapter completes successfully. */
  void markRedisSnapshotCached(MarketDataSnapshotCacheEntry entry);

  /**
   * Deletes only reconstructed projection state so an operator-controlled Kafka replay can rebuild
   * it.
   */
  void resetForReplay();
}
