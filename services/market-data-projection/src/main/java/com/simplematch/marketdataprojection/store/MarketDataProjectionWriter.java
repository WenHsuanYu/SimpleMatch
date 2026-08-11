package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionResult;
import com.simplematch.marketdataprojection.runtime.MarketDataSnapshotView;
import java.util.Optional;

/** Coordinates one atomic final-event projection write across focused durable adapters. */
final class MarketDataProjectionWriter {
  private final MarketDataInboxStore inboxStore;
  private final MarketDataOrderBookStore orderBookStore;
  private final MarketDataViewStore viewStore;
  private final MarketDataOutboxStore outboxStore;
  private final MarketDataProjectionProgressStore progressStore;
  private final MarketDataSnapshotEncoder snapshotEncoder;

  MarketDataProjectionWriter(
      MarketDataInboxStore inboxStore,
      MarketDataOrderBookStore orderBookStore,
      MarketDataViewStore viewStore,
      MarketDataOutboxStore outboxStore,
      MarketDataProjectionProgressStore progressStore,
      MarketDataSnapshotEncoder snapshotEncoder) {
    this.inboxStore = inboxStore;
    this.orderBookStore = orderBookStore;
    this.viewStore = viewStore;
    this.outboxStore = outboxStore;
    this.progressStore = progressStore;
    this.snapshotEncoder = snapshotEncoder;
  }

  MarketDataProjectionResult project(
      FinalMatchingEventEnvelope envelope,
      int kafkaPartition,
      long kafkaOffset,
      long observedAtUnixMs) {
    final MarketDataProjectionPosition position =
        new MarketDataProjectionPosition(kafkaPartition, kafkaOffset, observedAtUnixMs);
    if (inboxStore.isDuplicate(envelope)) {
      return MarketDataProjectionResult.duplicate();
    }
    progressStore.assertContiguous(position);

    final MatchingEvent event = envelope.event();
    final MarketDataBookMutation mutation = orderBookStore.apply(event, envelope.eventIdBytes());
    final MarketDataPreviousView previous = viewStore.findPrevious(mutation.instrument());
    final Optional<com.simplematch.marketdataprojection.runtime.LastTrade> lastTrade =
        mutation.lastTrade().isPresent() ? mutation.lastTrade() : previous.lastTrade();
    final MarketDataSnapshotView view =
        new MarketDataSnapshotView(
            event.getEventId(),
            event.getTradingSessionId(),
            mutation.instrument().venueMic(),
            mutation.instrument().symbol(),
            previous.sequence() + 1L,
            position.partition(),
            position.offset(),
            position.observedAtUnixMs(),
            lastTrade,
            orderBookStore.topFive(mutation.instrument(), Side.SIDE_BUY),
            orderBookStore.topFive(mutation.instrument(), Side.SIDE_SELL));
    final byte[] payload = snapshotEncoder.encode(view);
    final byte[] marketDataEventId = snapshotEncoder.eventId(view);

    inboxStore.insert(envelope, position);
    viewStore.upsert(view, marketDataEventId, payload);
    outboxStore.insert(marketDataEventId, envelope.eventIdBytes(), view, payload);
    progressStore.advance(position);
    return MarketDataProjectionResult.applied(view);
  }

  void resetForReplay() {
    outboxStore.reset();
    inboxStore.reset();
    orderBookStore.reset();
    viewStore.reset();
    progressStore.reset();
  }
}
