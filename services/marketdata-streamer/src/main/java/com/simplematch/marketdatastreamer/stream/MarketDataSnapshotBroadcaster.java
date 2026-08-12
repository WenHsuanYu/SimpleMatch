package com.simplematch.marketdatastreamer.stream;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Broadcasts complete market-data snapshots to symbol-filtered subscribers.
 *
 * <p>Each subscriber has a bounded queue and a dedicated daemon delivery loop. A slow subscriber
 * is terminated when that queue is full; the publisher never waits on a client and never lets a
 * client failure affect the Kafka consumer.
 */
public final class MarketDataSnapshotBroadcaster
    implements AutoCloseable, MarketDataSnapshotPublisher, MarketDataSnapshotSubscriptionSource {
  private final int queueCapacity;
  private final int maximumSubscribers;
  private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
  private final Object subscriberLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Creates a broadcaster with the supplied per-subscriber queue capacity. */
  public MarketDataSnapshotBroadcaster(int queueCapacity) {
    this(queueCapacity, 256);
  }

  /** Creates a broadcaster with bounded per-subscriber queues and subscriber count. */
  public MarketDataSnapshotBroadcaster(int queueCapacity, int maximumSubscribers) {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("queueCapacity must be positive");
    }
    if (maximumSubscribers <= 0) {
      throw new IllegalArgumentException("maximumSubscribers must be positive");
    }
    this.queueCapacity = queueCapacity;
    this.maximumSubscribers = maximumSubscribers;
  }

  /**
   * Registers a client for complete snapshots whose venue/symbol key is selected by the request.
   * An empty selection means every public instrument.
   *
   * @param venueSymbols selected keys in the form {@code venueMic:symbol}
   * @param observer client stream observer
   * @return a handle that removes the subscriber and completes its stream
   */
  @Override
  public MarketDataSnapshotSubscription subscribe(
      Set<String> venueSymbols, StreamObserver<MarketDataSnapshot> observer) {
    if (closed.get()) {
      throw new IllegalStateException("market-data broadcaster is closed");
    }
    final Set<String> selected = validateSymbols(venueSymbols);
    final Subscriber subscriber = new Subscriber(selected, observer, queueCapacity);
    synchronized (subscriberLock) {
      if (closed.get()) {
        throw new IllegalStateException("market-data broadcaster is closed");
      }
      if (subscribers.size() >= maximumSubscribers) {
        throw new IllegalArgumentException("market-data subscriber capacity is exhausted");
      }
      subscribers.add(subscriber);
      subscriber.start();
    }
    return () -> remove(subscriber, false);
  }

  /** Publishes one complete snapshot without waiting for any subscriber. */
  @Override
  public void publish(MarketDataSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (!snapshot.getIsSnapshot()) {
      throw new IllegalArgumentException("market-data stream accepts complete snapshots only");
    }
    for (Subscriber subscriber : subscribers) {
      if (subscriber.matches(snapshot) && !subscriber.offer(snapshot)) {
        remove(subscriber, true);
      }
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      final List<Subscriber> closing;
      synchronized (subscriberLock) {
        closing = new ArrayList<>(subscribers);
        subscribers.clear();
      }
      for (Subscriber subscriber : closing) {
        subscriber.complete();
      }
    }
  }

  private void remove(Subscriber subscriber, boolean slow) {
    if (subscribers.remove(subscriber)) {
      if (slow) {
        subscriber.fail(
            new IllegalStateException("slow market-data subscriber exceeded queue capacity"));
      } else {
        subscriber.complete();
      }
    }
  }

  private static Set<String> validateSymbols(Set<String> venueSymbols) {
    final Set<String> selected = Set.copyOf(Objects.requireNonNull(venueSymbols, "venueSymbols"));
    selected.forEach(
        symbol -> {
          if (symbol.isBlank()) {
            throw new IllegalArgumentException("market-data symbol must not be blank");
          }
        });
    return selected;
  }

  private static final class Subscriber {
    private final Set<String> selected;
    private final StreamObserver<MarketDataSnapshot> observer;
    private final ArrayBlockingQueue<MarketDataSnapshot> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread deliveryThread;

    private Subscriber(
        Set<String> selected,
        StreamObserver<MarketDataSnapshot> observer,
        int queueCapacity) {
      this.selected = selected;
      this.observer = Objects.requireNonNull(observer, "observer");
      this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    private boolean matches(MarketDataSnapshot snapshot) {
      final String venueSymbol = snapshot.getVenueMic() + ":" + snapshot.getSymbol();
      return selected.isEmpty()
          || selected.contains(venueSymbol)
          || selected.contains(snapshot.getSymbol());
    }

    private boolean offer(MarketDataSnapshot snapshot) {
      return closed.get() || queue.offer(snapshot);
    }

    private void start() {
      deliveryThread =
          new Thread(
              () -> {
                try {
                  while (!closed.get() || !queue.isEmpty()) {
                    final MarketDataSnapshot snapshot = queue.take();
                    observer.onNext(snapshot);
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } catch (RuntimeException failure) {
                  fail(failure);
                }
              },
              "market-data-stream-subscriber");
      deliveryThread.setDaemon(true);
      deliveryThread.start();
    }

    private void complete() {
      if (closed.compareAndSet(false, true)) {
        deliveryThread.interrupt();
        observer.onCompleted();
      }
    }

    private void fail(Throwable failure) {
      if (closed.compareAndSet(false, true)) {
        deliveryThread.interrupt();
        observer.onError(failure);
      }
    }
  }
}
