package com.simplematch.marketdataprojection.store;

import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionGapException;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores ordered source-progress checkpoints and explicit projection replay requirements. */
final class MarketDataProjectionProgressStore {
  private static final String READY = "READY";
  private static final String RESYNC_REQUIRED = "RESYNC_REQUIRED";
  private final JdbcTemplate jdbcTemplate;

  MarketDataProjectionProgressStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  void assertContiguous(MarketDataProjectionPosition position) {
    final List<MarketDataProjectionProgress> progress =
        jdbcTemplate.query(
            """
            SELECT last_processed_offset, recovery_state
            FROM market_data_projection.partition_projection_progress
            WHERE partition_id = ?
            """,
            (resultSet, ignored) ->
                new MarketDataProjectionProgress(resultSet.getLong(1), resultSet.getString(2)),
            position.partition());
    if (progress.isEmpty()) {
      return;
    }
    final MarketDataProjectionProgress current = progress.getFirst();
    if (!READY.equals(current.recoveryState())
        || position.offset() != current.lastProcessedOffset() + 1L) {
      throw new MarketDataProjectionGapException(
          "market-data projection requires replay from a clean checkpoint for "
              + new DeliveryPosition("matching.events", position.partition(), position.offset()));
    }
  }

  void markResyncRequired(int partition, long failedOffset, long observedAtUnixMs) {
    final MarketDataProjectionPosition position =
        new MarketDataProjectionPosition(partition, failedOffset, observedAtUnixMs);
    final List<Long> existingOffsets =
        jdbcTemplate.query(
            """
            SELECT last_processed_offset
            FROM market_data_projection.partition_projection_progress
            WHERE partition_id = ?
            """,
            (resultSet, ignored) -> resultSet.getLong(1),
            position.partition());
    final long lastProcessedOffset =
        existingOffsets.isEmpty()
            ? Math.max(0L, position.offset() - 1L)
            : existingOffsets.getFirst();
    upsert(position.partition(), lastProcessedOffset, RESYNC_REQUIRED, position.observedAtUnixMs());
  }

  void advance(MarketDataProjectionPosition position) {
    upsert(position.partition(), position.offset(), READY, position.observedAtUnixMs());
  }

  void reset() {
    jdbcTemplate.update("DELETE FROM market_data_projection.partition_projection_progress");
  }

  private void upsert(int partition, long offset, String state, long observedAtUnixMs) {
    if (isPostgres()) {
      jdbcTemplate.update(
          """
          INSERT INTO market_data_projection.partition_projection_progress (
            partition_id, last_processed_offset, recovery_state, updated_at_unix_ms
          ) VALUES (?, ?, ?, ?)
          ON CONFLICT (partition_id) DO UPDATE SET
            last_processed_offset = EXCLUDED.last_processed_offset,
            recovery_state = EXCLUDED.recovery_state,
            updated_at_unix_ms = EXCLUDED.updated_at_unix_ms
          """,
          partition,
          offset,
          state,
          observedAtUnixMs);
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO market_data_projection.partition_projection_progress (
          partition_id, last_processed_offset, recovery_state, updated_at_unix_ms
        ) KEY(partition_id) VALUES (?, ?, ?, ?)
        """,
        partition,
        offset,
        state,
        observedAtUnixMs);
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
