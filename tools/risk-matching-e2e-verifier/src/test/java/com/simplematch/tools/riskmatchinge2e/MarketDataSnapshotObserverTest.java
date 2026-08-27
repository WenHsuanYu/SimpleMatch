package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.marketdata.v1.MarketDataServiceGrpc;
import com.simplematch.contracts.marketdata.v1.SubscribeMarketDataRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Verifies deployed snapshot observation through the public market-data gRPC interface. */
class MarketDataSnapshotObserverTest {
  @Test
  void observesOneCompleteSnapshotForTheRequestedInstrument() throws Exception {
    final MarketDataSnapshot snapshot =
        MarketDataSnapshot.newBuilder()
            .setSchemaVersion(1)
            .setEventId("snapshot-3")
            .setSourceMatchingEventId("matching-event-3")
            .setTradingSessionId("2026-08-27-regular")
            .setVenueMic("XTAI")
            .setSymbol("2330")
            .setInstrumentSequence(3L)
            .setSourcePartitionId(4)
            .setSourceKafkaOffset(91L)
            .setGeneratedAtUnixMs(1_788_000_000_000L)
            .setIsSnapshot(true)
            .build();
    final Server server =
        ServerBuilder.forPort(0)
            .addService(
                new MarketDataServiceGrpc.MarketDataServiceImplBase() {
                  @Override
                  public void subscribeMarketDataSnapshots(
                      SubscribeMarketDataRequest request,
                      StreamObserver<MarketDataSnapshot> observer) {
                    assertThat(request.getSymbolsList()).containsExactly("XTAI:2330");
                    observer.onNext(snapshot);
                    observer.onCompleted();
                  }
                })
            .build()
            .start();

    try {
      final AtomicBoolean ready = new AtomicBoolean();
      final MarketDataSnapshotObserver.Observation observation =
          MarketDataSnapshotObserver.observe(
              "127.0.0.1",
              server.getPort(),
              "XTAI",
              "2330",
              Duration.ofSeconds(5),
              () -> ready.set(true));

      assertThat(ready).isTrue();
      assertThat(observation.eventId()).isEqualTo("snapshot-3");
      assertThat(observation.sourceMatchingEventId()).isEqualTo("matching-event-3");
      assertThat(observation.instrumentSequence()).isEqualTo(3L);
      assertThat(observation.sourcePartitionId()).isEqualTo(4);
      assertThat(observation.sourceKafkaOffset()).isEqualTo(91L);
      assertThat(observation.completeSnapshot()).isTrue();
    } finally {
      server.shutdownNow();
    }
  }
}
