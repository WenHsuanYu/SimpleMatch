package com.simplematch.marketdatastreamer.stream;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.marketdata.v1.MarketDataServiceGrpc;
import com.simplematch.contracts.marketdata.v1.SubscribeMarketDataRequest;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import java.util.Set;

/** Exposes the public complete-snapshot stream without giving clients projection ownership. */
public final class MarketDataGrpcService
    extends MarketDataServiceGrpc.MarketDataServiceImplBase {
  private final MarketDataSnapshotSubscriptionSource broadcaster;

  /** Creates the gRPC adapter over the local streaming seam. */
  public MarketDataGrpcService(MarketDataSnapshotSubscriptionSource broadcaster) {
    this.broadcaster = broadcaster;
  }

  @Override
  public void subscribeMarketDataSnapshots(
      SubscribeMarketDataRequest request, StreamObserver<MarketDataSnapshot> responseObserver) {
    final Set<String> symbols = new HashSet<>(request.getSymbolsList());
    final MarketDataSnapshotSubscription subscription;
    try {
      subscription = broadcaster.subscribe(symbols, responseObserver);
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription(invalid.getMessage())
              .asRuntimeException());
      return;
    }
    Context.current().addListener(ignored -> subscription.close(), Runnable::run);
  }
}
