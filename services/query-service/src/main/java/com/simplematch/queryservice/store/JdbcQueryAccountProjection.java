package com.simplematch.queryservice.store;

import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes the latest Account lifecycle summary for the query read model. */
final class JdbcQueryAccountProjection {
  private static final String INSERT_COLUMNS =
      "(account_id, lifecycle_state, reserved_notional_units, "
          + "reserved_quantity_shares, reason_code, reason_detail, source_event_id, "
          + "updated_at_unix_ms)";
  private static final String VALUES = "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

  private JdbcQueryAccountProjection() {}

  static void project(
      JdbcTemplate jdbcTemplate,
      AccountLifecycleEvent event,
      String eventId,
      QueryProjectionPosition position) {
    final Object[] values = {
      event.getAccountId(),
      event.getState().name(),
      event.getReservedNotional().getUnits(),
      event.getReservedQuantity().getShares(),
      event.getReasonCode(),
      event.getReasonDetail(),
      eventId,
      position.observedAtUnixMs()
    };
    if (isPostgres(jdbcTemplate)) {
      jdbcTemplate.update(
          "INSERT INTO query_service.account_summary_read_model "
              + INSERT_COLUMNS
              + " "
              + VALUES
              + " ON CONFLICT (account_id) DO UPDATE SET "
              + "lifecycle_state = EXCLUDED.lifecycle_state, "
              + "reserved_notional_units = EXCLUDED.reserved_notional_units, "
              + "reserved_quantity_shares = EXCLUDED.reserved_quantity_shares, "
              + "reason_code = EXCLUDED.reason_code, reason_detail = EXCLUDED.reason_detail, "
              + "source_event_id = EXCLUDED.source_event_id, "
              + "updated_at_unix_ms = EXCLUDED.updated_at_unix_ms",
          values);
    } else {
      jdbcTemplate.update(
          "MERGE INTO query_service.account_summary_read_model "
              + INSERT_COLUMNS
              + " KEY(account_id) "
              + VALUES,
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
}
