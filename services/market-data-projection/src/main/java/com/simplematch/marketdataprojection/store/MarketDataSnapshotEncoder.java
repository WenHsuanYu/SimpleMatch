package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.marketdataprojection.runtime.MarketDataSnapshotView;
import com.simplematch.marketdataprojection.runtime.PriceLevel;
import java.util.HexFormat;

/** Encodes complete market-data views into the deterministic public snapshot contract. */
final class MarketDataSnapshotEncoder {
  byte[] encode(MarketDataSnapshotView view) {
    final MarketDataSnapshot.Builder snapshot =
        MarketDataSnapshot.newBuilder()
            .setSchemaVersion(1)
            .setEventId(HexFormat.of().formatHex(eventId(view)))
            .setSourceMatchingEventId(view.sourceMatchingEventId())
            .setTradingSessionId(view.tradingSessionId())
            .setVenueMic(view.venueMic())
            .setSymbol(view.symbol())
            .setInstrumentSequence(view.instrumentSequence())
            .setSourcePartitionId(view.sourcePartitionId())
            .setSourceKafkaOffset(view.sourceKafkaOffset())
            .setGeneratedAtUnixMs(view.generatedAtUnixMs())
            .setIsSnapshot(view.isCompleteSnapshot());
    view.lastTrade()
        .ifPresent(
            trade ->
                snapshot
                    .setHasLastTrade(true)
                    .setLastTradePriceUnits(trade.priceUnits())
                    .setLastTradeQuantityShares(trade.quantityShares()));
    snapshot.addAllBids(view.bids().stream().map(this::protobufLevel).toList());
    snapshot.addAllAsks(view.asks().stream().map(this::protobufLevel).toList());
    return snapshot.build().toByteArray();
  }

  byte[] eventId(MarketDataSnapshotView view) {
    return FinalMatchingEventEnvelope.deterministicIdentity(
        "market-data-snapshot-v1", view.sourceMatchingEventId(), view.venueMic(), view.symbol());
  }

  private com.simplematch.contracts.marketdata.runtime.v1.PriceLevel protobufLevel(
      PriceLevel level) {
    return com.simplematch.contracts.marketdata.runtime.v1.PriceLevel.newBuilder()
        .setPriceUnits(level.priceUnits())
        .setQuantityShares(level.quantityShares())
        .build();
  }
}
