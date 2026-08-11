package com.simplematch.marketdataprojection.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A complete per-instrument public view derived from one ordered Matching Event. */
public record MarketDataSnapshotView(
    String sourceMatchingEventId,
    String tradingSessionId,
    String venueMic,
    String symbol,
    long instrumentSequence,
    int sourcePartitionId,
    long sourceKafkaOffset,
    long generatedAtUnixMs,
    Optional<LastTrade> lastTrade,
    List<PriceLevel> bids,
    List<PriceLevel> asks) {
  /** Defensively owns the complete snapshot contents and validates its Kafka provenance. */
  public MarketDataSnapshotView {
    if (sourceMatchingEventId == null || !sourceMatchingEventId.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("source matching event id must be lowercase SHA-256 hex");
    }
    if (tradingSessionId == null || tradingSessionId.isBlank()) {
      throw new IllegalArgumentException("trading session id must not be blank");
    }
    if (venueMic == null || venueMic.length() != 4 || symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("instrument identity is invalid");
    }
    if (instrumentSequence <= 0
        || sourcePartitionId < 0
        || sourcePartitionId > 14
        || sourceKafkaOffset < 0
        || generatedAtUnixMs < 0) {
      throw new IllegalArgumentException("market-data snapshot provenance is invalid");
    }
    Objects.requireNonNull(lastTrade, "lastTrade");
    bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
    asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
    if (bids.size() > 5 || asks.size() > 5) {
      throw new IllegalArgumentException(
          "a Phase 1 market-data snapshot contains at most five levels");
    }
  }

  /** Phase 1 deliberately publishes complete snapshots rather than dependent deltas. */
  public boolean isCompleteSnapshot() {
    return true;
  }
}
