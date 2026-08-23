package com.simplematch.queryservice.store;

import com.simplematch.contracts.DeterministicTextIdentity;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

/** Writes final Matching Event order and execution projections. */
final class JdbcQueryMatchingProjection {
  private static final String ORDER_INSERT_COLUMNS =
      "(order_id, account_id, venue_mic, symbol, side, state, "
          + "leaves_quantity_shares, last_event_id, source_partition_id, "
          + "source_offset_value, updated_at_unix_ms)";
  private static final String ORDER_VALUES = "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final String EXECUTION_INSERT_COLUMNS =
      "(execution_id, order_id, account_id, venue_mic, symbol, side, "
          + "fill_quantity_shares, fill_price_units, cumulative_quantity_shares, "
          + "leaves_quantity_shares, average_price_units, source_event_id, "
          + "source_partition_id, source_offset_value, executed_at_unix_ms)";
  private static final String EXECUTION_VALUES =
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private JdbcQueryMatchingProjection() {}

  static void project(
      JdbcOperations jdbcTemplate,
      MatchingEvent event,
      String eventId,
      QueryProjectionPosition position) {
    switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED ->
          upsertOrder(
              jdbcTemplate,
              new OrderProjection(
                  event.getOrderRested().getOrderId(),
                  event.getOrderRested().getAccountId(),
                  new InstrumentProjection(
                      event.getOrderRested().getInstrument().getVenueMic(),
                      event.getOrderRested().getInstrument().getSymbol()),
                  event.getOrderRested().getSide().name(),
                  "RESTING",
                  event.getOrderRested().getLeavesQuantityShares(),
                  new ProjectionSource(eventId, position)));
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED ->
          projectTrade(jdbcTemplate, event, eventId, position);
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          projectTerminal(
              jdbcTemplate,
              new TerminalProjection(
                  event.getOrderCancelled().getOrderId(),
                  event.getOrderCancelled().getAccountId(),
                  new InstrumentProjection(
                      event.getOrderCancelled().getInstrument().getVenueMic(),
                      event.getOrderCancelled().getInstrument().getSymbol()),
                  event.getOrderCancelled().getSide().name(),
                  event.getOrderCancelled().getLeavesQuantityShares(),
                  "CANCELED",
                  new ProjectionSource(eventId, position)));
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
          projectTerminal(
              jdbcTemplate,
              new TerminalProjection(
                  event.getOrderExpired().getOrderId(),
                  event.getOrderExpired().getAccountId(),
                  new InstrumentProjection(
                      event.getOrderExpired().getInstrument().getVenueMic(),
                      event.getOrderExpired().getInstrument().getSymbol()),
                  event.getOrderExpired().getSide().name(),
                  event.getOrderExpired().getLeavesQuantityShares(),
                  "EXPIRED",
                  new ProjectionSource(eventId, position)));
      default -> throw new IllegalArgumentException("final Matching Event type is required");
    }
  }

  private static void projectTrade(
      JdbcOperations jdbcTemplate,
      MatchingEvent event,
      String eventId,
      QueryProjectionPosition position) {
    final var trade = event.getTradeExecuted();
    final InstrumentProjection instrument =
        new InstrumentProjection(
            trade.getInstrument().getVenueMic(), trade.getInstrument().getSymbol());
    final ProjectionSource source = new ProjectionSource(eventId, position);
    projectTradeLeg(
        jdbcTemplate,
        new TradeLegProjection(
            trade.getMaker(),
            instrument,
            "maker",
            source,
            trade.getQuantityShares(),
            trade.getPriceUnits()));
    projectTradeLeg(
        jdbcTemplate,
        new TradeLegProjection(
            trade.getTaker(),
            instrument,
            "taker",
            source,
            trade.getQuantityShares(),
            trade.getPriceUnits()));
  }

  private static void projectTradeLeg(JdbcOperations jdbcTemplate, TradeLegProjection projection) {
    final TradeLeg leg = projection.leg();
    final String executionId =
        DeterministicTextIdentity.uuid(
                "simplematch.query-execution-v1",
                projection.source().eventId(),
                leg.getOrderId(),
                projection.role())
            .toString();
    upsertOrder(
        jdbcTemplate,
        new OrderProjection(
            leg.getOrderId(),
            leg.getAccountId(),
            projection.instrument(),
            leg.getSide().name(),
            orderState(leg),
            leg.getLeavesQuantityShares(),
            projection.source()));
    upsertExecution(
        jdbcTemplate,
        new ExecutionProjection(
            executionId,
            leg,
            projection.instrument(),
            projection.source(),
            projection.quantityShares(),
            projection.priceUnits()));
  }

  private static String orderState(TradeLeg leg) {
    return switch (leg.getResultingState()) {
      case TRADE_LEG_STATE_FILLED -> "FILLED";
      case TRADE_LEG_STATE_PARTIALLY_FILLED -> "PARTIALLY_FILLED";
      case TRADE_LEG_STATE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("validated trade leg state is required");
    };
  }

  private static void projectTerminal(JdbcOperations jdbcTemplate, TerminalProjection projection) {
    upsertOrder(
        jdbcTemplate,
        new OrderProjection(
            projection.orderId(),
            projection.accountId(),
            projection.instrument(),
            projection.side(),
            projection.state(),
            projection.leavesQuantity(),
            projection.source()));
  }

  private static void upsertOrder(JdbcOperations jdbcTemplate, OrderProjection projection) {
    final Object[] values = {
      projection.orderId(),
      projection.accountId(),
      projection.instrument().venueMic(),
      projection.instrument().symbol(),
      projection.side(),
      projection.state(),
      projection.leavesQuantity(),
      projection.source().eventId(),
      projection.source().position().partition(),
      projection.source().position().offset(),
      projection.source().position().observedAtUnixMs()
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
      JdbcOperations jdbcTemplate, ExecutionProjection projection) {
    final TradeLeg leg = projection.leg();
    final Object[] values = {
      projection.executionId(),
      leg.getOrderId(),
      leg.getAccountId(),
      projection.instrument().venueMic(),
      projection.instrument().symbol(),
      leg.getSide().name(),
      projection.quantityShares(),
      projection.priceUnits(),
      leg.getCumulativeQuantityShares(),
      leg.getLeavesQuantityShares(),
      leg.getAveragePriceUnits(),
      projection.source().eventId(),
      projection.source().position().partition(),
      projection.source().position().offset(),
      projection.source().position().observedAtUnixMs()
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

  private static boolean isPostgres(JdbcOperations jdbcTemplate) {
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
      InstrumentProjection instrument,
      String side,
      String state,
      long leavesQuantity,
      ProjectionSource source) {}

  private record InstrumentProjection(String venueMic, String symbol) {}

  private record ProjectionSource(String eventId, QueryProjectionPosition position) {}

  private record TerminalProjection(
      String orderId,
      String accountId,
      InstrumentProjection instrument,
      String side,
      long leavesQuantity,
      String state,
      ProjectionSource source) {}

  private record TradeLegProjection(
      TradeLeg leg,
      InstrumentProjection instrument,
      String role,
      ProjectionSource source,
      long quantityShares,
      long priceUnits) {}

  private record ExecutionProjection(
      String executionId,
      TradeLeg leg,
      InstrumentProjection instrument,
      ProjectionSource source,
      long quantityShares,
      long priceUnits) {}
}
