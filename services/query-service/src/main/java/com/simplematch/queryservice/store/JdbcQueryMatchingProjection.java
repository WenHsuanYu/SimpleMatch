package com.simplematch.queryservice.store;

import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes final Matching Event order and execution projections. */
final class JdbcQueryMatchingProjection {
  private static final String ORDER_INSERT_COLUMNS =
      "(order_id, account_id, venue_mic, symbol, side, state, "
          + "leaves_quantity_shares, last_event_id, source_partition_id, "
          + "source_offset_value, updated_at_unix_ms)";
  private static final String ORDER_VALUES =
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final String EXECUTION_INSERT_COLUMNS =
      "(execution_id, order_id, account_id, venue_mic, symbol, side, "
          + "fill_quantity_shares, fill_price_units, cumulative_quantity_shares, "
          + "leaves_quantity_shares, average_price_units, source_event_id, "
          + "source_partition_id, source_offset_value, executed_at_unix_ms)";
  private static final String EXECUTION_VALUES =
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private JdbcQueryMatchingProjection() {}

  static void project(
      JdbcTemplate jdbcTemplate, MatchingEvent event, QueryProjectionPosition position) {
    switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED ->
          upsertOrder(
              jdbcTemplate,
              new OrderProjection(
                  event.getOrderRested().getOrderId(),
                  event.getOrderRested().getAccountId(),
                  event.getOrderRested().getInstrument().getVenueMic(),
                  event.getOrderRested().getInstrument().getSymbol(),
                  event.getOrderRested().getSide().name(),
                  "RESTING",
                  event.getOrderRested().getLeavesQuantityShares(),
                  event.getEventId(),
                  position));
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED -> projectTrade(jdbcTemplate, event, position);
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          projectTerminal(
              jdbcTemplate,
              event.getOrderCancelled().getOrderId(),
              event.getOrderCancelled().getAccountId(),
              event.getOrderCancelled().getInstrument().getVenueMic(),
              event.getOrderCancelled().getInstrument().getSymbol(),
              event.getOrderCancelled().getSide().name(),
              event.getOrderCancelled().getLeavesQuantityShares(),
              event.getEventId(),
              position);
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
          projectTerminal(
              jdbcTemplate,
              event.getOrderExpired().getOrderId(),
              event.getOrderExpired().getAccountId(),
              event.getOrderExpired().getInstrument().getVenueMic(),
              event.getOrderExpired().getInstrument().getSymbol(),
              event.getOrderExpired().getSide().name(),
              event.getOrderExpired().getLeavesQuantityShares(),
              event.getEventId(),
              position);
      default -> throw new IllegalArgumentException("final Matching Event type is required");
    }
  }

  private static void projectTrade(
      JdbcTemplate jdbcTemplate, MatchingEvent event, QueryProjectionPosition position) {
    final var trade = event.getTradeExecuted();
    final String venueMic = trade.getInstrument().getVenueMic();
    final String symbol = trade.getInstrument().getSymbol();
    projectTradeLeg(jdbcTemplate, event, trade.getMaker(), venueMic, symbol, "maker", position);
    projectTradeLeg(jdbcTemplate, event, trade.getTaker(), venueMic, symbol, "taker", position);
  }

  private static void projectTradeLeg(
      JdbcTemplate jdbcTemplate,
      MatchingEvent event,
      TradeLeg leg,
      String venueMic,
      String symbol,
      String role,
      QueryProjectionPosition position) {
    final String executionId =
        com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope
            .deterministicUuid(
                "simplematch.query-execution-v1", event.getEventId(), leg.getOrderId(), role)
            .toString();
    upsertOrder(
        jdbcTemplate,
        new OrderProjection(
            leg.getOrderId(),
            leg.getAccountId(),
            venueMic,
            symbol,
            leg.getSide().name(),
            leg.getLeavesQuantityShares() == 0 ? "FILLED" : "PARTIALLY_FILLED",
            leg.getLeavesQuantityShares(),
            event.getEventId(),
            position));
    upsertExecution(jdbcTemplate, executionId, leg, venueMic, symbol, event.getEventId(), position);
  }

  private static void projectTerminal(
      JdbcTemplate jdbcTemplate,
      String orderId,
      String accountId,
      String venueMic,
      String symbol,
      String side,
      long leavesQuantity,
      String eventId,
      QueryProjectionPosition position) {
    upsertOrder(
        jdbcTemplate,
        new OrderProjection(
            orderId,
            accountId,
            venueMic,
            symbol,
            side,
            "CANCELED",
            leavesQuantity,
            eventId,
            position));
  }

  private static void upsertOrder(JdbcTemplate jdbcTemplate, OrderProjection projection) {
    final Object[] values = {
      projection.orderId(),
      projection.accountId(),
      projection.venueMic(),
      projection.symbol(),
      projection.side(),
      projection.state(),
      projection.leavesQuantity(),
      projection.eventId(),
      projection.position().partition(),
      projection.position().offset(),
      projection.position().observedAtUnixMs()
    };
    if (isPostgres(jdbcTemplate)) {
      jdbcTemplate.update(
          "INSERT INTO query_service.order_read_model "
              + ORDER_INSERT_COLUMNS
              + " "
              + ORDER_VALUES
              + " ON CONFLICT (order_id) DO UPDATE SET "
              + "account_id = EXCLUDED.account_id, venue_mic = EXCLUDED.venue_mic, "
              + "symbol = EXCLUDED.symbol, side = EXCLUDED.side, state = EXCLUDED.state, "
              + "leaves_quantity_shares = EXCLUDED.leaves_quantity_shares, "
              + "last_event_id = EXCLUDED.last_event_id, "
              + "source_partition_id = EXCLUDED.source_partition_id, "
              + "source_offset_value = EXCLUDED.source_offset_value, "
              + "updated_at_unix_ms = EXCLUDED.updated_at_unix_ms",
          values);
    } else {
      jdbcTemplate.update(
          "MERGE INTO query_service.order_read_model "
              + ORDER_INSERT_COLUMNS
              + " KEY(order_id) "
              + ORDER_VALUES,
          values);
    }
  }

  private static void upsertExecution(
      JdbcTemplate jdbcTemplate,
      String executionId,
      TradeLeg leg,
      String venueMic,
      String symbol,
      String sourceEventId,
      QueryProjectionPosition position) {
    final Object[] values = {
      executionId,
      leg.getOrderId(),
      leg.getAccountId(),
      venueMic,
      symbol,
      leg.getSide().name(),
      leg.getQuantityShares(),
      leg.getPriceUnits(),
      leg.getCumulativeQuantityShares(),
      leg.getLeavesQuantityShares(),
      leg.getAveragePriceUnits(),
      sourceEventId,
      position.partition(),
      position.offset(),
      position.observedAtUnixMs()
    };
    if (isPostgres(jdbcTemplate)) {
      jdbcTemplate.update(
          "INSERT INTO query_service.execution_read_model "
              + EXECUTION_INSERT_COLUMNS
              + " "
              + EXECUTION_VALUES
              + " ON CONFLICT (execution_id) DO UPDATE SET "
              + "order_id = EXCLUDED.order_id, account_id = EXCLUDED.account_id, "
              + "venue_mic = EXCLUDED.venue_mic, symbol = EXCLUDED.symbol, "
              + "side = EXCLUDED.side, fill_quantity_shares = EXCLUDED.fill_quantity_shares, "
              + "fill_price_units = EXCLUDED.fill_price_units, "
              + "cumulative_quantity_shares = EXCLUDED.cumulative_quantity_shares, "
              + "leaves_quantity_shares = EXCLUDED.leaves_quantity_shares, "
              + "average_price_units = EXCLUDED.average_price_units, "
              + "source_event_id = EXCLUDED.source_event_id, "
              + "source_partition_id = EXCLUDED.source_partition_id, "
              + "source_offset_value = EXCLUDED.source_offset_value, "
              + "executed_at_unix_ms = EXCLUDED.executed_at_unix_ms",
          values);
    } else {
      jdbcTemplate.update(
          "MERGE INTO query_service.execution_read_model "
              + EXECUTION_INSERT_COLUMNS
              + " KEY(execution_id) "
              + EXECUTION_VALUES,
          values);
    }
  }

  private static boolean isPostgres(JdbcTemplate jdbcTemplate) {
    return Boolean.TRUE.equals(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                connection
                    .getMetaData()
                    .getDatabaseProductName()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("postgresql")));
  }

  private record OrderProjection(
      String orderId,
      String accountId,
      String venueMic,
      String symbol,
      String side,
      String state,
      long leavesQuantity,
      String eventId,
      QueryProjectionPosition position) {}
}
