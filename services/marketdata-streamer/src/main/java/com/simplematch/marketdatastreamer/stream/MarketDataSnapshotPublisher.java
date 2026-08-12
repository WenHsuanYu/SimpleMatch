package com.simplematch.marketdatastreamer.stream;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;

/** Accepts complete public market-data snapshots from the Kafka adapter. */
@FunctionalInterface
public interface MarketDataSnapshotPublisher {
  /** Publishes one complete snapshot without waiting for subscribers. */
  void publish(MarketDataSnapshot snapshot);
}
