package com.simplematch.marketdatastreamer.stream;

/** Handle used by a gRPC adapter to cancel one market-data subscription. */
@FunctionalInterface
public interface MarketDataSnapshotSubscription extends AutoCloseable {
  @Override
  void close();
}
