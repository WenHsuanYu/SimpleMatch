package com.simplematch.persistence.store;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stores Persistence's rebuildable current-order projection with database-specific upsert syntax.
 */
final class JdbcMatchingOrderProjectionStore {
  private final JdbcTemplate jdbcTemplate;

  JdbcMatchingOrderProjectionStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  void upsert(byte[] eventId, MatchingOrderProjection projection) {
    final Object[] arguments = {
      UUID.fromString(projection.orderId()),
      UUID.fromString(projection.accountId()),
      projection.venueMic(),
      projection.symbol(),
      MatchingEventSqlValues.sideCode(projection.side()),
      projection.status(),
      projection.cumulativeQuantityShares(),
      projection.leavesQuantityShares(),
      eventId
    };
    if (isPostgres()) {
      jdbcTemplate.update(
          """
          INSERT INTO persistence.matching_order_projections (
            order_id, account_id, venue_mic, symbol, side, status, cumulative_quantity_shares,
            leaves_quantity_shares, last_event_id
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (order_id) DO UPDATE SET
            account_id = EXCLUDED.account_id,
            venue_mic = EXCLUDED.venue_mic,
            symbol = EXCLUDED.symbol,
            side = EXCLUDED.side,
            status = EXCLUDED.status,
            cumulative_quantity_shares = EXCLUDED.cumulative_quantity_shares,
            leaves_quantity_shares = EXCLUDED.leaves_quantity_shares,
            last_event_id = EXCLUDED.last_event_id
          """,
          arguments);
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO persistence.matching_order_projections (
          order_id, account_id, venue_mic, symbol, side, status, cumulative_quantity_shares,
          leaves_quantity_shares, last_event_id
        ) KEY(order_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        arguments);
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
