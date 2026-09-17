package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataServiceGrpc;
import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.marketdata.runtime.v1.SubscribeMarketDataRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Observes one public market-data subscription across a server replacement. */
final class MarketDataSnapshotRecoveryObserver {
  private static final long RETRY_DELAY_MILLIS = 250;

  private MarketDataSnapshotRecoveryObserver() {}

  /**
   * Subscribes, waits for the stream to end, and resubscribes until two snapshots are observed.
   *
   * @param host gRPC server host
   * @param port gRPC server port
   * @param venueMic requested venue MIC
   * @param symbol requested instrument symbol
   * @param timeout total bounded observation duration
   * @param signals files used to coordinate the live replacement
   * @return connection and snapshot evidence for the two subscriptions
   */
  static RecoveryEvidence observe(
      String host,
      int port,
      String venueMic,
      String symbol,
      Duration timeout,
      RecoverySignals signals) {
    final String requiredHost = Support.requireText(host, "host");
    final String requiredVenueMic = Support.requireText(venueMic, "venue MIC");
    final String requiredSymbol = Support.requireText(symbol, "symbol");
    Support.validateEndpoint(port, timeout);
    return observeAttempts(
        new ObservationContext(
            requiredHost,
            port,
            requiredVenueMic,
            requiredSymbol,
            timeout,
            signals));
  }

  private static RecoveryEvidence observeAttempts(ObservationContext context) {
    int attempt = 0;
    while (shouldAttempt(context)) {
      attempt++;
      final AttemptState state = new AttemptState();
      runAttempt(context, state, attempt);
      completeAttempt(context, state, attempt);
    }
    requireTwoSnapshots(context);
    return new RecoveryEvidence(
        List.copyOf(context.connections), context.snapshotCopy());
  }

  private static boolean shouldAttempt(ObservationContext context) {
    return context.snapshotCount() < 2 && System.nanoTime() < context.deadline;
  }

  private static void completeAttempt(
      ObservationContext context, AttemptState state, int attempt) {
    context.connections.add(state.evidence(attempt));
    if (state.failure != null) {
      throw state.failure;
    }
    if (!state.ended()) {
      throw new IllegalStateException("market-data subscription did not end before timeout");
    }
    if (context.snapshotCount() < 2) {
      Support.awaitSignal(context.signals.replacementReady(), context.deadline);
    }
    Support.sleepBeforeRetry(context.deadline);
  }

  private static void requireTwoSnapshots(ObservationContext context) {
    if (context.snapshotCount() != 2) {
      throw new IllegalStateException("market-data stream did not resubscribe successfully");
    }
  }

  private static void runAttempt(ObservationContext context, AttemptState state, int attempt) {
    final ManagedChannel channel =
        ManagedChannelBuilder.forAddress(context.host, context.port).usePlaintext().build();
    try {
      MarketDataServiceGrpc.newStub(channel)
          .withDeadlineAfter(Support.remainingMillis(context.deadline), TimeUnit.MILLISECONDS)
          .subscribeMarketDataSnapshots(
              context.request, new RecoveryStreamObserver(context, state));
      Support.writeSignal(context.signals.subscriptionReady(attempt));
      awaitAttempt(state, context);
    } finally {
      channel.shutdownNow();
    }
  }

  private static void awaitAttempt(AttemptState state, ObservationContext context) {
    while (System.nanoTime() < context.deadline && context.snapshotCount() < 2) {
      try {
        if (state.end.await(
            Math.min(250, Support.remainingMillis(context.deadline)), TimeUnit.MILLISECONDS)) {
          return;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "market-data recovery observation was interrupted", interrupted);
      }
    }
  }

  private static void signalDisconnectIfNeeded(AttemptState state, ObservationContext context) {
    if (state.snapshotObserved && context.snapshotCount() == 1) {
      Support.writeSignal(context.signals.disconnectedReady());
    }
  }

  private static final class RecoveryStreamObserver implements StreamObserver<MarketDataSnapshot> {
    private final ObservationContext context;
    private final AttemptState state;

    private RecoveryStreamObserver(ObservationContext context, AttemptState state) {
      this.context = context;
      this.state = state;
    }

    @Override
    public void onNext(MarketDataSnapshot snapshot) {
      if (!Support.matches(snapshot, context.venueMic, context.symbol)) {
        return;
      }
      if (!snapshot.getIsSnapshot()) {
        state.fail(new IllegalStateException("market-data stream returned a delta"));
        return;
      }
      final int snapshotNumber = context.recordSnapshot(state, snapshot);
      if (snapshotNumber == 1) {
        Support.writeSignal(context.signals.initialSnapshotReady());
      } else if (snapshotNumber == 2) {
        Support.writeSignal(context.signals.resubscribedSnapshotReady());
        state.end.countDown();
      }
    }

    @Override
    public void onError(Throwable failure) {
      state.terminate("ERROR:" + Status.fromThrowable(failure).getCode());
      signalDisconnectIfNeeded(state, context);
    }

    @Override
    public void onCompleted() {
      state.terminate("COMPLETED");
      signalDisconnectIfNeeded(state, context);
    }
  }

  private static final class Support {
    private Support() {}

    private static boolean matches(MarketDataSnapshot snapshot, String venueMic, String symbol) {
      return venueMic.equals(snapshot.getVenueMic()) && symbol.equals(snapshot.getSymbol());
    }

    private static long remainingMillis(long deadline) {
      return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static void sleepBeforeRetry(long deadline) {
      if (System.nanoTime() >= deadline) {
        return;
      }
      try {
        Thread.sleep(Math.min(RETRY_DELAY_MILLIS, remainingMillis(deadline)));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "market-data recovery retry was interrupted", interrupted);
      }
    }

    private static void awaitSignal(Path signal, long deadline) {
      if (signal == null) {
        return;
      }
      while (System.nanoTime() < deadline) {
        if (Files.isRegularFile(signal)) {
          return;
        }
        sleepBeforeRetry(deadline);
      }
      throw new IllegalStateException("replacement signal was not observed before timeout");
    }

    private static void validateEndpoint(int port, Duration timeout) {
      if (port < 1 || port > 65_535) {
        throw new IllegalArgumentException("port must be between 1 and 65535");
      }
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
    }

    private static String requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " is required");
      }
      return value;
    }

    private static void writeSignal(Path path) {
      if (path == null) {
        return;
      }
      try {
        final Path parent = path.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(path, "READY\n");
      } catch (Exception failure) {
        throw new IllegalStateException("cannot write market-data recovery signal", failure);
      }
    }
  }

  private static final class ObservationContext {
    private final String host;
    private final int port;
    private final String venueMic;
    private final String symbol;
    private final long deadline;
    private final RecoverySignals signals;
    private final SubscribeMarketDataRequest request;
    private final Object snapshotLock = new Object();
    private final List<MarketDataSnapshotObserver.Observation> snapshots = new ArrayList<>();
    private final List<ConnectionEvidence> connections = new ArrayList<>();

    private ObservationContext(
        String host,
        int port,
        String venueMic,
        String symbol,
        Duration timeout,
        RecoverySignals signals) {
      this.host = host;
      this.port = port;
      this.venueMic = venueMic;
      this.symbol = symbol;
      this.deadline = System.nanoTime() + timeout.toNanos();
      this.signals = signals;
      this.request =
          SubscribeMarketDataRequest.newBuilder().addSymbols(venueMic + ":" + symbol).build();
    }

    private int recordSnapshot(AttemptState state, MarketDataSnapshot snapshot) {
      synchronized (snapshotLock) {
        if (state.snapshotObserved) {
          return snapshots.size();
        }
        state.snapshotObserved = true;
        snapshots.add(MarketDataSnapshotObserver.Observation.from(snapshot));
        return snapshots.size();
      }
    }

    private int snapshotCount() {
      synchronized (snapshotLock) {
        return snapshots.size();
      }
    }

    private List<MarketDataSnapshotObserver.Observation> snapshotCopy() {
      synchronized (snapshotLock) {
        return List.copyOf(snapshots);
      }
    }
  }

  private static final class AttemptState {
    private final CountDownLatch end = new CountDownLatch(1);
    private volatile boolean snapshotObserved;
    private volatile String termination = "TIMEOUT";
    private volatile RuntimeException failure;

    private void terminate(String value) {
      termination = value;
      end.countDown();
    }

    private void fail(RuntimeException value) {
      failure = value;
      terminate("INVALID_SNAPSHOT");
    }

    private boolean ended() {
      return end.getCount() == 0;
    }

    private ConnectionEvidence evidence(int attempt) {
      return new ConnectionEvidence(attempt, snapshotObserved, termination);
    }
  }

  /** Coordinates the client and the live replacement runner. */
  record RecoverySignals(
      Path initialSubscriptionReady,
      Path initialSnapshotReady,
      Path disconnectedReady,
      Path replacementReady,
      Path reconnectedReady,
      Path resubscribedSnapshotReady) {
    Path subscriptionReady(int attempt) {
      return attempt == 1 ? initialSubscriptionReady : reconnectedReady;
    }
  }

  /** Captures one connection attempt and whether it delivered a matching snapshot. */
  record ConnectionEvidence(int attempt, boolean snapshotObserved, String termination) {}

  /** Captures the two snapshots and the subscription attempts used for recovery. */
  record RecoveryEvidence(
      List<ConnectionEvidence> connections,
      List<MarketDataSnapshotObserver.Observation> snapshots) {
    RecoveryEvidence {
      connections = List.copyOf(connections);
      snapshots = List.copyOf(snapshots);
    }
  }
}
