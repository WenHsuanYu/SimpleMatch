package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.marketdata.runtime.v1.MarketDataServiceGrpc;
import com.simplematch.contracts.marketdata.runtime.v1.SubscribeMarketDataRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the bounded public subscription reconnect behavior used by live certification. */
class MarketDataSnapshotRecoveryObserverTest {
  @Test
  void reconnectsTheSameSubscriptionAfterTheFirstStreamEnds(@TempDir Path temporaryDirectory)
      throws Exception {
    final AtomicInteger subscriptions = new AtomicInteger();
    final Server server =
        ServerBuilder.forPort(0)
            .addService(
                new MarketDataServiceGrpc.MarketDataServiceImplBase() {
                  @Override
                  public void subscribeMarketDataSnapshots(
                      SubscribeMarketDataRequest request,
                      StreamObserver<MarketDataSnapshot> observer) {
                    assertThat(request.getSymbolsList()).containsExactly("XTAI:2330");
                    observer.onNext(snapshot(subscriptions.incrementAndGet()));
                    if (subscriptions.get() == 1) {
                      observer.onError(Status.UNAVAILABLE.asRuntimeException());
                    } else {
                      observer.onCompleted();
                    }
                  }
                })
            .build()
            .start();
    final Path initialSubscription = temporaryDirectory.resolve("initial-subscription");
    final Path initialSnapshot = temporaryDirectory.resolve("initial-snapshot");
    final Path disconnected = temporaryDirectory.resolve("disconnected");
    final Path replacementReady = temporaryDirectory.resolve("replacement-ready");
    final Path reconnected = temporaryDirectory.resolve("reconnected");
    final Path resubscribedSnapshot = temporaryDirectory.resolve("resubscribed-snapshot");
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      final Future<MarketDataSnapshotRecoveryObserver.RecoveryEvidence> observation =
          executor.submit(
              () ->
                  MarketDataSnapshotRecoveryObserver.observe(
                      "127.0.0.1",
                      server.getPort(),
                      "XTAI",
                      "2330",
                      Duration.ofSeconds(5),
                      new MarketDataSnapshotRecoveryObserver.RecoverySignals(
                          initialSubscription,
                          initialSnapshot,
                          disconnected,
                          replacementReady,
                          reconnected,
                          resubscribedSnapshot)));

      for (int attempt = 0; attempt < 500 && !Files.exists(disconnected); attempt++) {
        Thread.sleep(10L);
      }
      assertThat(Files.exists(disconnected)).isTrue();
      assertThat(observation).isNotDone();
      Files.writeString(replacementReady, "READY\n");
      final MarketDataSnapshotRecoveryObserver.RecoveryEvidence evidence =
          observation.get(5, TimeUnit.SECONDS);

      assertThat(evidence.snapshots()).extracting("instrumentSequence").containsExactly(1L, 2L);
      assertThat(evidence.connections()).hasSize(2);
      assertThat(evidence.connections().get(0).snapshotObserved()).isTrue();
      assertThat(evidence.connections().get(0).termination()).startsWith("ERROR:");
      assertThat(evidence.connections().get(1).snapshotObserved()).isTrue();
      assertThat(Files.exists(initialSubscription)).isTrue();
      assertThat(Files.exists(initialSnapshot)).isTrue();
      assertThat(Files.exists(disconnected)).isTrue();
      assertThat(Files.exists(reconnected)).isTrue();
      assertThat(Files.exists(resubscribedSnapshot)).isTrue();
    } finally {
      executor.shutdownNow();
      server.shutdownNow();
    }
  }

  private static MarketDataSnapshot snapshot(int sequence) {
    return MarketDataSnapshot.newBuilder()
        .setSchemaVersion(1)
        .setEventId("snapshot-" + sequence)
        .setSourceMatchingEventId("matching-event-" + sequence)
        .setTradingSessionId("2026-08-27-regular")
        .setVenueMic("XTAI")
        .setSymbol("2330")
        .setInstrumentSequence(sequence)
        .setSourcePartitionId(0)
        .setSourceKafkaOffset(sequence)
        .setGeneratedAtUnixMs(sequence)
        .setIsSnapshot(true)
        .build();
  }
}
