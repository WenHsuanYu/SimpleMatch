package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.marketdata.v1.MarketDataServiceGrpc;
import com.simplematch.contracts.marketdata.v1.SubscribeMarketDataRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Observes one complete snapshot through the public market-data streaming interface. */
public final class MarketDataSnapshotObserver {
  private MarketDataSnapshotObserver() {}

  /**
   * Waits for the requested instrument's next complete snapshot.
   *
   * @param host gRPC server host
   * @param port gRPC server port
   * @param venueMic ISO 10383 venue MIC
   * @param symbol venue-local instrument symbol
   * @param timeout maximum observation duration
   * @return the observed complete snapshot
   */
  public static Observation observe(
      String host, int port, String venueMic, String symbol, Duration timeout) {
    return observe(host, port, venueMic, symbol, timeout, () -> {});
  }

  static Observation observe(
      String host,
      int port,
      String venueMic,
      String symbol,
      Duration timeout,
      Runnable readySignal) {
    final String requiredHost = requireText(host, "host");
    final String requiredVenueMic = requireText(venueMic, "venue MIC");
    final String requiredSymbol = requireText(symbol, "symbol");
    validateCall(port, timeout, readySignal);

    final ManagedChannel channel =
        ManagedChannelBuilder.forAddress(requiredHost, port).usePlaintext().build();
    try {
      final SubscribeMarketDataRequest request =
          SubscribeMarketDataRequest.newBuilder()
              .addSymbols(requiredVenueMic + ":" + requiredSymbol)
              .build();
      final var snapshots =
          MarketDataServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
              .subscribeMarketDataSnapshots(request);
      readySignal.run();
      while (snapshots.hasNext()) {
        final MarketDataSnapshot snapshot = snapshots.next();
        if (matches(snapshot, requiredVenueMic, requiredSymbol)) {
          return Observation.from(requireCompleteSnapshot(snapshot));
        }
      }
      throw new IllegalStateException(
          "market-data stream completed before a snapshot was observed");
    } finally {
      channel.shutdownNow();
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static void validateCall(int port, Duration timeout, Runnable readySignal) {
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(readySignal, "readySignal");
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  private static boolean matches(MarketDataSnapshot snapshot, String venueMic, String symbol) {
    return venueMic.equals(snapshot.getVenueMic()) && symbol.equals(snapshot.getSymbol());
  }

  private static MarketDataSnapshot requireCompleteSnapshot(MarketDataSnapshot snapshot) {
    if (!snapshot.getIsSnapshot()) {
      throw new IllegalStateException("market-data stream returned a delta before a snapshot");
    }
    return snapshot;
  }

  /** Machine-readable evidence for one public market-data snapshot. */
  public record Observation(
      int schemaVersion,
      String eventId,
      String sourceMatchingEventId,
      String tradingSessionId,
      String venueMic,
      String symbol,
      long instrumentSequence,
      int sourcePartitionId,
      long sourceKafkaOffset,
      long generatedAtUnixMs,
      boolean completeSnapshot,
      LastTradeObservation lastTrade,
      List<PriceLevelObservation> bids,
      List<PriceLevelObservation> asks) {
    /** Retains immutable price-level evidence at the interface boundary. */
    public Observation {
      bids = List.copyOf(bids);
      asks = List.copyOf(asks);
    }

    @Override
    public List<PriceLevelObservation> bids() {
      return List.copyOf(bids);
    }

    @Override
    public List<PriceLevelObservation> asks() {
      return List.copyOf(asks);
    }

    private static Observation from(MarketDataSnapshot snapshot) {
      final LastTradeObservation lastTrade =
          snapshot.getHasLastTrade()
              ? new LastTradeObservation(
                  snapshot.getLastTradePriceUnits(), snapshot.getLastTradeQuantityShares())
              : null;
      return new Observation(
          snapshot.getSchemaVersion(),
          snapshot.getEventId(),
          snapshot.getSourceMatchingEventId(),
          snapshot.getTradingSessionId(),
          snapshot.getVenueMic(),
          snapshot.getSymbol(),
          snapshot.getInstrumentSequence(),
          snapshot.getSourcePartitionId(),
          snapshot.getSourceKafkaOffset(),
          snapshot.getGeneratedAtUnixMs(),
          snapshot.getIsSnapshot(),
          lastTrade,
          snapshot.getBidsList().stream()
              .map(
                  level ->
                      new PriceLevelObservation(
                          level.getPriceUnits(), level.getQuantityShares()))
              .toList(),
          snapshot.getAsksList().stream()
              .map(
                  level ->
                      new PriceLevelObservation(
                          level.getPriceUnits(), level.getQuantityShares()))
              .toList());
    }
  }

  /** Last trade carried by a complete snapshot. */
  public record LastTradeObservation(long priceUnits, long quantityShares) {}

  /** Aggregated order-book level carried by a complete snapshot. */
  public record PriceLevelObservation(long priceUnits, long quantityShares) {}
}
