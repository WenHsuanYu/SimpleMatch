package com.simplematch.persistence.store;

import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stores immutable trade/fill facts and delegates mutable order projections to their own adapter.
 */
final class MatchingEventFactStore {
  private final JdbcTemplate jdbcTemplate;
  private final JdbcMatchingOrderProjectionStore projectionStore;

  MatchingEventFactStore(
      JdbcTemplate jdbcTemplate, JdbcMatchingOrderProjectionStore projectionStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.projectionStore = projectionStore;
  }

  void persist(byte[] eventId, MatchingEvent event) {
    switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED ->
          projectionStore.upsert(
              eventId,
              new MatchingOrderProjection(
                  event.getOrderRested().getOrderId(),
                  event.getOrderRested().getAccountId(),
                  event.getOrderRested().getInstrument().getVenueMic(),
                  event.getOrderRested().getInstrument().getSymbol(),
                  event.getOrderRested().getSide(),
                  "RESTING",
                  0,
                  event.getOrderRested().getLeavesQuantityShares()));
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED -> persistTrade(eventId, event);
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          persistTerminal(eventId, event.getOrderCancelled(), "CANCELLED");
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
          persistTerminal(eventId, event.getOrderExpired(), "EXPIRED");
      default -> throw new IllegalArgumentException("validated matching event type is required");
    }
  }

  private void persistTrade(byte[] eventId, MatchingEvent event) {
    final var trade = event.getTradeExecuted();
    final byte[] tradeId = trade.getTradeId().toByteArray();
    jdbcTemplate.update(
        """
        INSERT INTO persistence.trades (
          trade_id, event_id, trading_day, trading_session_id, partition_id, source_input_offset,
          venue_mic, symbol, quantity_shares, price_units
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tradeId,
        eventId,
        LocalDate.parse(event.getArtifactIdentity().getTradingDay()),
        event.getTradingSessionId(),
        event.getPartitionId(),
        event.getSourceInputOffset(),
        trade.getInstrument().getVenueMic(),
        trade.getInstrument().getSymbol(),
        trade.getQuantityShares(),
        trade.getPriceUnits());
    insertFill(tradeId, 1, trade.getMaker(), trade.getQuantityShares(), trade.getPriceUnits());
    insertFill(tradeId, 2, trade.getTaker(), trade.getQuantityShares(), trade.getPriceUnits());
    upsertTradeProjection(
        eventId,
        trade.getMaker(),
        trade.getInstrument().getVenueMic(),
        trade.getInstrument().getSymbol());
    upsertTradeProjection(
        eventId,
        trade.getTaker(),
        trade.getInstrument().getVenueMic(),
        trade.getInstrument().getSymbol());
  }

  private void insertFill(
      byte[] tradeId, int role, TradeLeg leg, long quantityShares, long priceUnits) {
    jdbcTemplate.update(
        """
        INSERT INTO persistence.order_fills (
          trade_id, leg_role, order_id, account_id, side, quantity_shares, price_units,
          cumulative_quantity_shares, leaves_quantity_shares
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tradeId,
        role,
        UUID.fromString(leg.getOrderId()),
        UUID.fromString(leg.getAccountId()),
        MatchingEventSqlValues.sideCode(leg.getSide()),
        quantityShares,
        priceUnits,
        leg.getCumulativeQuantityShares(),
        leg.getLeavesQuantityShares());
  }

  private void upsertTradeProjection(byte[] eventId, TradeLeg leg, String venueMic, String symbol) {
    projectionStore.upsert(
        eventId,
        new MatchingOrderProjection(
            leg.getOrderId(),
            leg.getAccountId(),
            venueMic,
            symbol,
            leg.getSide(),
            MatchingEventSqlValues.status(leg.getResultingState()),
            leg.getCumulativeQuantityShares(),
            leg.getLeavesQuantityShares()));
  }

  private void persistTerminal(byte[] eventId, OrderTerminal terminal, String status) {
    projectionStore.upsert(
        eventId,
        new MatchingOrderProjection(
            terminal.getOrderId(),
            terminal.getAccountId(),
            terminal.getInstrument().getVenueMic(),
            terminal.getInstrument().getSymbol(),
            terminal.getSide(),
            status,
            0,
            terminal.getLeavesQuantityShares()));
  }
}
