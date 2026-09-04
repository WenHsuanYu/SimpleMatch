package com.simplematch.marketdatastreamer.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.marketdata.runtime.v1.SubscribeMarketDataRequest;
import io.grpc.stub.StreamObserver;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarketDataGrpcServiceTest {
  @Test
  void routesTheVersionedSnapshotSubscriptionToTheBoundedSource() {
    final AtomicReference<Set<String>> requestedSymbols = new AtomicReference<>();
    final AtomicBoolean closed = new AtomicBoolean();
    final MarketDataGrpcService service =
        new MarketDataGrpcService(
            (symbols, ignored) -> {
              requestedSymbols.set(symbols);
              return () -> closed.set(true);
            });
    final RecordingObserver observer = new RecordingObserver();

    service.subscribeMarketDataSnapshots(
        SubscribeMarketDataRequest.newBuilder().addSymbols("XTAI:2330").build(), observer);

    assertThat(requestedSymbols).hasValue(Set.of("XTAI:2330"));
    assertThat(closed).isFalse();
  }

  private static final class RecordingObserver implements StreamObserver<MarketDataSnapshot> {
    @Override
    public void onNext(MarketDataSnapshot value) {}

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onCompleted() {}
  }
}
