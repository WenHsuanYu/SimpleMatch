package com.simplematch.queryservice.security;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit migrated read-model fixtures; these DML rows do not claim Kafka replay evidence. */
final class QueryOrderHttpFixtures {
  static final QuerySecurityFixtures POLICY = QueryAccountHttpFixtures.POLICY;
  static final Order X_EXECUTED = order("X-with-executions", QueryAccountHttpFixtures.X, true);
  static final Order X_EMPTY = order("X-empty", QueryAccountHttpFixtures.X, false);
  static final Order Y_EXECUTED = order("Y-with-executions", QueryAccountHttpFixtures.Y, true);
  static final Order Y_EMPTY = order("Y-empty", QueryAccountHttpFixtures.Y, false);
  static final List<Order> ORDERS = List.of(X_EXECUTED, X_EMPTY, Y_EXECUTED, Y_EMPTY);
  static final UUID MISSING_PARENT = UUID.randomUUID();

  private QueryOrderHttpFixtures() {}

  static void seed(JdbcTemplate jdbc) {
    for (int index = 0; index < ORDERS.size(); index++) {
      final Order order = ORDERS.get(index);
      final boolean executed = order.executionId() != null;
      final String side = order.accountId().toString().equals(QueryAccountHttpFixtures.X)
          ? "SIDE_BUY" : "SIDE_SELL";
      final String eventId = eventId(executed ? "shared-fixture-trade" : order.name());
      jdbc.update("INSERT INTO query_service.order_read_model "
          + "(order_id, account_id, venue_mic, symbol, side, state, leaves_quantity_shares, "
          + "last_event_id, source_partition_id, source_offset_value, updated_at_unix_ms) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          order.orderId(), order.accountId(), "XTAI", "2330", side,
          executed ? "PARTIALLY_FILLED" : "RESTING", executed ? 5L : 10L,
          eventId, 0, executed ? 4L : index, 1000L);
      if (executed) {
        seedExecution(jdbc, order, side, eventId);
      }
    }
    jdbc.update("INSERT INTO query_service.projection_checkpoint "
        + "(source_topic, partition_id, last_processed_offset, recovery_state, updated_at_unix_ms) "
        + "VALUES (?, ?, ?, ?, ?)", "matching.events", 0, 4L, "READY", 1000L);
  }

  static Order named(String name) {
    return ORDERS.stream().filter(order -> order.name().equals(name)).findFirst().orElseThrow();
  }

  private static void seedExecution(JdbcTemplate jdbc, Order order, String side, String eventId) {
    jdbc.update("INSERT INTO query_service.execution_read_model "
        + "(execution_id, order_id, account_id, venue_mic, symbol, side, fill_quantity_shares, "
        + "fill_price_units, cumulative_quantity_shares, leaves_quantity_shares, average_price_units, "
        + "source_event_id, source_partition_id, source_offset_value, executed_at_unix_ms) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        order.executionId(), order.orderId(), order.accountId(), "XTAI", "2330", side,
        5L, 100L, 5L, 5L, 100L, eventId, 0, 4L, 1000L);
  }

  private static Order order(String name, String accountId, boolean executed) {
    return new Order(name, UUID.randomUUID(), UUID.fromString(accountId),
        executed ? UUID.randomUUID() : null);
  }

  private static String eventId(String label) {
    return HexFormat.of().formatHex(
        FinalMatchingEventEnvelope.sha256(label.getBytes(StandardCharsets.UTF_8)));
  }

  record Order(String name, UUID orderId, UUID accountId, UUID executionId) {}
}
