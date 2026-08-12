package com.simplematch.marketdatastreamer.stream;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import io.grpc.stub.StreamObserver;
import java.util.Set;

/** Registers bounded public market-data snapshot subscriptions. */
public interface MarketDataSnapshotSubscriptionSource {
  /** Registers a client for the selected venue-qualified or symbol-only instruments. */
  MarketDataSnapshotSubscription subscribe(
      Set<String> venueSymbols, StreamObserver<MarketDataSnapshot> observer);
}
