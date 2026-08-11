package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheEntry;
import com.simplematch.marketdataprojection.kafka.MarketDataOutboxRecord;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionResult;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC facade for the market-data projection's transaction-owned durable operations.
 *
 * <p>The projection writer composes the inbox, order book, view, outbox, and progress adapters in
 * the caller-owned transaction. This facade separately exposes delivery, cache, and replay work.
 */
public class JdbcMarketDataProjectionStore implements MarketDataProjectionStore {
  private final MarketDataProjectionWriter projectionWriter;
  private final MarketDataProjectionProgressStore progressStore;
  private final MarketDataOutboxStore outboxStore;
  private final MarketDataViewStore viewStore;

  /** Creates the service-local projection storage adapter. */
  public JdbcMarketDataProjectionStore(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, "marketdata.events");
  }

  /** Creates the service-local projection storage adapter for the configured output topic. */
  public JdbcMarketDataProjectionStore(JdbcTemplate jdbcTemplate, String marketDataTopic) {
    final MarketDataProjectionProgressStore localProgressStore =
        new MarketDataProjectionProgressStore(jdbcTemplate);
    final MarketDataInboxStore inboxStore = new MarketDataInboxStore(jdbcTemplate);
    final MarketDataOrderBookStore orderBookStore = new MarketDataOrderBookStore(jdbcTemplate);
    viewStore = new MarketDataViewStore(jdbcTemplate);
    outboxStore = new MarketDataOutboxStore(jdbcTemplate, marketDataTopic);
    progressStore = localProgressStore;
    projectionWriter =
        new MarketDataProjectionWriter(
            inboxStore,
            orderBookStore,
            viewStore,
            outboxStore,
            localProgressStore,
            new MarketDataSnapshotEncoder());
  }

  @Override
  public MarketDataProjectionResult project(
      FinalMatchingEventEnvelope envelope,
      int kafkaPartition,
      long kafkaOffset,
      long observedAtUnixMs) {
    return projectionWriter.project(envelope, kafkaPartition, kafkaOffset, observedAtUnixMs);
  }

  @Override
  public void markResyncRequired(int partition, long failedOffset, long observedAtUnixMs) {
    progressStore.markResyncRequired(partition, failedOffset, observedAtUnixMs);
  }

  @Override
  public List<MarketDataOutboxRecord> pendingOutbox(int limit) {
    return outboxStore.pending(limit);
  }

  @Override
  public void markOutboxPublished(MarketDataOutboxRecord record, long publishedAtUnixMs) {
    outboxStore.markPublished(record, publishedAtUnixMs);
  }

  @Override
  public List<MarketDataSnapshotCacheEntry> pendingRedisSnapshots(int limit) {
    return viewStore.pendingRedisSnapshots(limit);
  }

  @Override
  public void markRedisSnapshotCached(MarketDataSnapshotCacheEntry entry) {
    viewStore.markRedisSnapshotCached(entry);
  }

  @Override
  public void resetForReplay() {
    projectionWriter.resetForReplay();
  }
}
