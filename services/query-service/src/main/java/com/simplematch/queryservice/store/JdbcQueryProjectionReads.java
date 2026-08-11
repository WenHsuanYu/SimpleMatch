package com.simplematch.queryservice.store;

import com.simplematch.queryservice.model.QueryAccountSummaryView;
import com.simplematch.queryservice.model.QueryExecutionView;
import com.simplematch.queryservice.model.QueryFreshness;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.model.QueryOrderView;
import java.sql.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcOperations;

/** Reads durable query projections and source freshness from PostgreSQL or H2. */
final class JdbcQueryProjectionReads {
  private JdbcQueryProjectionReads() {}

  static Optional<QueryOrderView> findOrder(JdbcOperations jdbcTemplate, String orderId) {
    return jdbcTemplate
        .query(
            "SELECT order_id, account_id, venue_mic, symbol, side, state, "
                + "leaves_quantity_shares, last_event_id, updated_at_unix_ms "
                + "FROM query_service.order_read_model WHERE order_id = ?",
            JdbcQueryProjectionRowMappers::order,
            orderId)
        .stream()
        .findFirst();
  }

  static List<QueryExecutionView> findExecutions(JdbcOperations jdbcTemplate, String orderId) {
    return jdbcTemplate.query(
        "SELECT execution_id, order_id, account_id, venue_mic, symbol, side, "
            + "fill_quantity_shares, fill_price_units, cumulative_quantity_shares, "
            + "leaves_quantity_shares, average_price_units, source_event_id, "
            + "executed_at_unix_ms FROM query_service.execution_read_model "
            + "WHERE order_id = ? ORDER BY executed_at_unix_ms, execution_id",
        JdbcQueryProjectionRowMappers::execution,
        orderId);
  }

  static Optional<QueryAccountSummaryView> findAccountSummary(
      JdbcOperations jdbcTemplate, String accountId) {
    return jdbcTemplate
        .query(
            "SELECT account_id, lifecycle_state, reserved_notional_units, "
                + "reserved_quantity_shares, reason_code, reason_detail, source_event_id, "
                + "updated_at_unix_ms FROM query_service.account_summary_read_model "
                + "WHERE account_id = ?",
            JdbcQueryProjectionRowMappers::account,
            accountId)
        .stream()
        .findFirst();
  }

  static Optional<QueryMarketReferenceView> findMarketReference(
      JdbcOperations jdbcTemplate, String tradingDay, String venueMic, String symbol) {
    return jdbcTemplate
        .query(
            "SELECT trading_day, artifact_id, venue_mic, symbol, market_rule_id, "
                + "reference_price_units, lower_price_limit_units, upper_price_limit_units, "
                + "routing_partition, updated_at_unix_ms "
                + "FROM query_service.active_market_reference "
                + "WHERE trading_day = ? AND venue_mic = ? AND symbol = ?",
            JdbcQueryProjectionRowMappers::marketReference,
            Date.valueOf(tradingDay),
            venueMic,
            symbol)
        .stream()
        .findFirst();
  }

  static QueryFreshness freshness(JdbcOperations jdbcTemplate) {
    return new QueryFreshness(
        jdbcTemplate
            .query(
                "SELECT source_topic, partition_id, last_processed_offset, recovery_state, "
                    + "updated_at_unix_ms FROM query_service.projection_checkpoint "
                    + "ORDER BY source_topic, partition_id",
                JdbcQueryProjectionRowMappers::freshness)
            .stream()
            .toList());
  }
}
