package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.marketdataprojection.runtime.LastTrade;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionGapException;
import com.simplematch.marketdataprojection.runtime.PriceLevel;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies final Matching Event mutations to the rebuildable order-book projection. */
final class MarketDataOrderBookStore {
  private final JdbcTemplate jdbcTemplate;

  MarketDataOrderBookStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  MarketDataBookMutation apply(MatchingEvent event, byte[] eventId) {
    final MarketDataInstrument instrument = MarketDataInstrument.from(event);
    final Optional<LastTrade> lastTrade =
        switch (event.getEventType()) {
          case MATCHING_EVENT_TYPE_ORDER_RESTED -> {
            applyRested(event.getOrderRested(), eventId);
            yield Optional.empty();
          }
          case MATCHING_EVENT_TYPE_TRADE_EXECUTED ->
              Optional.of(applyTrade(event.getTradeExecuted(), eventId));
          case MATCHING_EVENT_TYPE_ORDER_CANCELLED -> {
            deleteTerminal(event.getOrderCancelled());
            yield Optional.empty();
          }
          case MATCHING_EVENT_TYPE_ORDER_EXPIRED -> {
            deleteTerminal(event.getOrderExpired());
            yield Optional.empty();
          }
          case MATCHING_EVENT_TYPE_UNSPECIFIED, UNRECOGNIZED ->
              throw new IllegalArgumentException("matching event type is required");
        };
    return new MarketDataBookMutation(instrument, lastTrade);
  }

  List<PriceLevel> topFive(MarketDataInstrument instrument, Side side) {
    final String order = side == Side.SIDE_BUY ? "DESC" : "ASC";
    final String query =
        "SELECT price_units, SUM(leaves_quantity_shares) "
            + "FROM market_data_projection.order_book_entries "
            + "WHERE venue_mic = ? AND symbol = ? AND side = ? "
            + "GROUP BY price_units "
            + "ORDER BY price_units "
            + order
            + " LIMIT 5";
    return jdbcTemplate.query(
        query,
        (resultSet, ignored) -> new PriceLevel(resultSet.getLong(1), resultSet.getLong(2)),
        instrument.venueMic(),
        instrument.symbol(),
        MarketDataSqlValues.sideCode(side));
  }

  void reset() {
    jdbcTemplate.update("DELETE FROM market_data_projection.order_book_entries");
  }

  private void applyRested(OrderRested rested, byte[] eventId) {
    final Object[] values = {
      UUID.fromString(rested.getOrderId()),
      rested.getInstrument().getVenueMic(),
      rested.getInstrument().getSymbol(),
      MarketDataSqlValues.sideCode(rested.getSide()),
      rested.getRestingPriceUnits(),
      rested.getLeavesQuantityShares(),
      eventId
    };
    if (isPostgres()) {
      jdbcTemplate.update(
          """
          INSERT INTO market_data_projection.order_book_entries (
            order_id, venue_mic, symbol, side, price_units, leaves_quantity_shares, last_event_id
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (order_id) DO UPDATE SET
            venue_mic = EXCLUDED.venue_mic,
            symbol = EXCLUDED.symbol,
            side = EXCLUDED.side,
            price_units = EXCLUDED.price_units,
            leaves_quantity_shares = EXCLUDED.leaves_quantity_shares,
            last_event_id = EXCLUDED.last_event_id
          """,
          values);
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO market_data_projection.order_book_entries (
          order_id, venue_mic, symbol, side, price_units, leaves_quantity_shares, last_event_id
        ) KEY(order_id) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        values);
  }

  private LastTrade applyTrade(TradeExecuted trade, byte[] eventId) {
    final UUID makerOrderId = UUID.fromString(trade.getMaker().getOrderId());
    final int affected;
    if (trade.getMaker().getLeavesQuantityShares() == 0L) {
      affected =
          jdbcTemplate.update(
              "DELETE FROM market_data_projection.order_book_entries WHERE order_id = ?",
              makerOrderId);
    } else {
      affected =
          jdbcTemplate.update(
              """
              UPDATE market_data_projection.order_book_entries
              SET leaves_quantity_shares = ?, last_event_id = ?
              WHERE order_id = ?
              """,
              trade.getMaker().getLeavesQuantityShares(),
              eventId,
              makerOrderId);
    }
    if (affected != 1) {
      throw new MarketDataProjectionGapException(
          "a trade referenced a resting maker order that is absent from the projection");
    }
    return new LastTrade(trade.getPriceUnits(), trade.getQuantityShares());
  }

  private void deleteTerminal(OrderTerminal terminal) {
    jdbcTemplate.update(
        "DELETE FROM market_data_projection.order_book_entries WHERE order_id = ?",
        UUID.fromString(terminal.getOrderId()));
  }

  private boolean isPostgres() {
    return Boolean.TRUE.equals(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    connection
                        .getMetaData()
                        .getDatabaseProductName()
                        .toLowerCase(Locale.ROOT)
                        .contains("postgresql")));
  }
}
