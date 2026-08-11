package com.simplematch.quickfixgateway.store;

import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIntent;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryRecipient;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryReport;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL operations for durable FIX delivery-intent rows and their socket-send state. */
final class FinalFixDeliveryIntentStore {
  private static final FinalFixDeliveryIntentRowMapper ROW_MAPPER =
      new FinalFixDeliveryIntentRowMapper();

  private FinalFixDeliveryIntentStore() {}

  static void insertAll(JdbcTemplate jdbcTemplate, List<FinalFixDeliveryIntent> intents) {
    for (FinalFixDeliveryIntent intent : intents) {
      insert(jdbcTemplate, intent);
    }
  }

  static List<FinalFixDeliveryIntent> findPending(JdbcTemplate jdbcTemplate, int maximumBatchSize) {
    if (maximumBatchSize <= 0) {
      throw new IllegalArgumentException("maximumBatchSize must be positive");
    }
    return jdbcTemplate.query(
        """
        SELECT delivery_id, event_id, recipient_order_id, delivery_index, source_partition,
          source_offset, session_id, order_id, client_order_id, symbol, side, order_quantity,
          exec_id, exec_type, ord_status, last_quantity, last_price_units, cumulative_quantity,
          leaves_quantity, average_price_units, text, created_at_unix_ms
        FROM quickfix_gateway.fix_delivery_intents
        WHERE status = 'PENDING'
        ORDER BY source_partition, source_offset, delivery_index
        LIMIT ?
        """,
        ROW_MAPPER,
        maximumBatchSize);
  }

  static boolean markSent(JdbcTemplate jdbcTemplate, String deliveryId, long sentAtUnixMs) {
    return jdbcTemplate.update(
            """
            UPDATE quickfix_gateway.fix_delivery_intents
            SET status = 'SENT', sent_at_unix_ms = ?
            WHERE delivery_id = ? AND status = 'PENDING'
            """,
            sentAtUnixMs,
            FinalFixDeliverySqlValues.binaryIdentity(deliveryId))
        == 1;
  }

  static Long oldestPendingCreatedAtUnixMs(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForObject(
        """
        SELECT MIN(created_at_unix_ms)
        FROM quickfix_gateway.fix_delivery_intents
        WHERE status = 'PENDING'
        """,
        Long.class);
  }

  static long pendingIntentCount(JdbcTemplate jdbcTemplate) {
    final Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM quickfix_gateway.fix_delivery_intents WHERE status = 'PENDING'",
            Long.class);
    return count == null ? 0L : count;
  }

  private static void insert(JdbcTemplate jdbcTemplate, FinalFixDeliveryIntent intent) {
    final FinalFixDeliveryRecipient recipient = intent.recipient();
    final FinalFixDeliveryReport report = intent.report();
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.fix_delivery_intents (
          delivery_id, event_id, recipient_order_id, delivery_index, source_partition,
          source_offset, session_id, order_id, client_order_id, symbol, side, order_quantity,
          exec_id, exec_type, ord_status, last_quantity, last_price_units, cumulative_quantity,
          leaves_quantity, average_price_units, text, status, created_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
        """,
        FinalFixDeliverySqlValues.binaryIdentity(intent.identity().deliveryId()),
        FinalFixDeliverySqlValues.binaryIdentity(intent.identity().eventId()),
        recipient.orderId(),
        intent.identity().deliveryIndex(),
        intent.sourcePartition(),
        intent.sourceOffset(),
        recipient.sessionId().toString(),
        recipient.order().orderId().value(),
        recipient.order().clientOrderId().value(),
        recipient.order().symbol().value(),
        FinalFixDeliverySqlValues.sideCode(recipient.order().side()),
        recipient.order().quantity().value(),
        report.executionId(),
        Character.toString(report.executionType()),
        Character.toString(report.orderStatus()),
        report.lastQuantity(),
        report.lastPriceUnits(),
        report.cumulativeQuantity(),
        report.leavesQuantity(),
        report.averagePriceUnits(),
        report.text(),
        intent.createdAtUnixMs());
  }
}
