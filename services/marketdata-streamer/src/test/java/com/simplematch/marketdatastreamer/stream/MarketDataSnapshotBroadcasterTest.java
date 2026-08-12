package com.simplematch.marketdatastreamer.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MarketDataSnapshotBroadcasterTest {
  @Test
  void publishesOnlyMatchingSymbolsAndKeepsCompleteSnapshots() throws Exception {
    final MarketDataSnapshotBroadcaster broadcaster = new MarketDataSnapshotBroadcaster(4);
    final RecordingObserver observer = new RecordingObserver();
    try (MarketDataSnapshotSubscription ignored =
        broadcaster.subscribe(Set.of("XTAI:2330"), observer)) {
      broadcaster.publish(snapshot("XTAI", "2330", 7));
      broadcaster.publish(snapshot("XTAI", "2317", 8));

      assertThat(observer.await(1)).isTrue();
      assertThat(observer.values).extracting(MarketDataSnapshot::getSymbol).containsExactly("2330");
      assertThat(observer.values.getFirst().getInstrumentSequence()).isEqualTo(7);
      assertThat(observer.values.getFirst().getBidsCount()).isEqualTo(1);
      assertThat(observer.values.getFirst().getAsksCount()).isEqualTo(1);
    }
  }

  @Test
  void acceptsTheProtoSymbolOnlyFilterAcrossVenues() throws Exception {
    final MarketDataSnapshotBroadcaster broadcaster = new MarketDataSnapshotBroadcaster(4);
    final RecordingObserver observer = new RecordingObserver();
    try (MarketDataSnapshotSubscription ignored =
        broadcaster.subscribe(Set.of("2330"), observer)) {
      broadcaster.publish(snapshot("XTAI", "2330", 7));

      assertThat(observer.await(1)).isTrue();
      assertThat(observer.values).extracting(MarketDataSnapshot::getSymbol).containsExactly("2330");
    }
  }

  @Test
  void rejectsBlankSymbolsAtTheSubscriptionSeam() {
    final MarketDataSnapshotBroadcaster broadcaster = new MarketDataSnapshotBroadcaster(4);

    assertThatThrownBy(() -> broadcaster.subscribe(Set.of(" "), new RecordingObserver()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("symbol");
  }

  @Test
  void rejectsSubscriptionsAfterTheConfiguredBulkheadIsFull() {
    final MarketDataSnapshotBroadcaster broadcaster = new MarketDataSnapshotBroadcaster(4, 1);
    final RecordingObserver first = new RecordingObserver();
    final MarketDataSnapshotSubscription ignored = broadcaster.subscribe(Set.of(), first);

    try {
      assertThatThrownBy(() -> broadcaster.subscribe(Set.of(), new RecordingObserver()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("capacity");
    } finally {
      ignored.close();
      broadcaster.close();
    }
  }

  @Test
  void terminatesAStalledSubscriberWhenItsBoundedQueueIsFull() throws Exception {
    final MarketDataSnapshotBroadcaster broadcaster = new MarketDataSnapshotBroadcaster(1);
    final BlockingObserver observer = new BlockingObserver();
    try (MarketDataSnapshotSubscription ignored =
        broadcaster.subscribe(Set.of(), observer)) {
      broadcaster.publish(snapshot("XTAI", "2330", 1));
      assertThat(observer.started.await(1, TimeUnit.SECONDS)).isTrue();

      broadcaster.publish(snapshot("XTAI", "2330", 2));
      broadcaster.publish(snapshot("XTAI", "2330", 3));

      assertThat(observer.error.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(observer.failure).hasMessageContaining("slow market-data subscriber");
    }
  }

  private static MarketDataSnapshot snapshot(String venue, String symbol, long sequence) {
    return MarketDataSnapshot.newBuilder()
        .setSchemaVersion(1)
        .setEventId("event-" + sequence)
        .setSourceMatchingEventId("matching-event-" + sequence)
        .setTradingSessionId("2026-08-12-regular")
        .setVenueMic(venue)
        .setSymbol(symbol)
        .setInstrumentSequence(sequence)
        .setSourcePartitionId(0)
        .setSourceKafkaOffset(sequence)
        .setGeneratedAtUnixMs(sequence)
        .setIsSnapshot(true)
        .addBids(
            com.simplematch.contracts.marketdata.runtime.v1.PriceLevel.newBuilder()
                .setPriceUnits(100)
                .setQuantityShares(10)
                .build())
        .addAsks(
            com.simplematch.contracts.marketdata.runtime.v1.PriceLevel.newBuilder()
                .setPriceUnits(101)
                .setQuantityShares(11)
                .build())
        .build();
  }

  private static class RecordingObserver implements StreamObserver<MarketDataSnapshot> {
    private final List<MarketDataSnapshot> values = new ArrayList<>();

    @Override
    public void onNext(MarketDataSnapshot value) {
      values.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      // The broadcaster owns completion semantics; this observer only records values in this test.
    }

    @Override
    public void onCompleted() {}

    boolean await(int expected) throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      while (values.size() < expected && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      return values.size() >= expected;
    }
  }

  private static final class BlockingObserver implements StreamObserver<MarketDataSnapshot> {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch error = new CountDownLatch(1);
    private Throwable failure;

    @Override
    public void onNext(MarketDataSnapshot value) {
      started.countDown();
      try {
        Thread.sleep(Duration.ofSeconds(5));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      failure = throwable;
      error.countDown();
    }

    @Override
    public void onCompleted() {}
  }
}
