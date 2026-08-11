package com.simplematch.marketdataprojection.store;

import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheEntry;
import com.simplematch.marketdataprojection.runtime.LastTrade;
import com.simplematch.marketdataprojection.runtime.MarketDataSnapshotView;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores the rebuildable complete per-instrument snapshot and Redis cache-repair state. */
final class MarketDataViewStore {
  private final JdbcTemplate jdbcTemplate;

  MarketDataViewStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  MarketDataPreviousView findPrevious(MarketDataInstrument instrument) {
    final List<MarketDataPreviousView> values =
        jdbcTemplate.query(
            """
            SELECT instrument_sequence, last_trade_price_units, last_trade_quantity_shares
            FROM market_data_projection.instrument_market_data
            WHERE venue_mic = ? AND symbol = ?
            """,
            (resultSet, ignored) ->
                new MarketDataPreviousView(
                    resultSet.getLong(1),
                    resultSet.getObject(2) == null
                        ? Optional.empty()
                        : Optional.of(new LastTrade(resultSet.getLong(2), resultSet.getLong(3)))),
            instrument.venueMic(),
            instrument.symbol());
    return values.isEmpty() ? new MarketDataPreviousView(0L, Optional.empty()) : values.getFirst();
  }

  void upsert(MarketDataSnapshotView view, byte[] eventId, byte[] payload) {
    final Object[] values = {
      view.venueMic(),
      view.symbol(),
      view.instrumentSequence(),
      view.lastTrade().map(LastTrade::priceUnits).orElse(null),
      view.lastTrade().map(LastTrade::quantityShares).orElse(null),
      eventId,
      payload,
      view.sourcePartitionId(),
      view.sourceKafkaOffset(),
      true,
      view.generatedAtUnixMs()
    };
    if (isPostgres()) {
      jdbcTemplate.update(
          """
          INSERT INTO market_data_projection.instrument_market_data (
            venue_mic, symbol, instrument_sequence, last_trade_price_units,
            last_trade_quantity_shares, snapshot_event_id, snapshot_payload, source_partition_id,
            source_offset_value, redis_snapshot_pending, updated_at_unix_ms
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (venue_mic, symbol) DO UPDATE SET
            instrument_sequence = EXCLUDED.instrument_sequence,
            last_trade_price_units = EXCLUDED.last_trade_price_units,
            last_trade_quantity_shares = EXCLUDED.last_trade_quantity_shares,
            snapshot_event_id = EXCLUDED.snapshot_event_id,
            snapshot_payload = EXCLUDED.snapshot_payload,
            source_partition_id = EXCLUDED.source_partition_id,
            source_offset_value = EXCLUDED.source_offset_value,
            redis_snapshot_pending = EXCLUDED.redis_snapshot_pending,
            updated_at_unix_ms = EXCLUDED.updated_at_unix_ms
          """,
          values);
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO market_data_projection.instrument_market_data (
          venue_mic, symbol, instrument_sequence, last_trade_price_units,
          last_trade_quantity_shares, snapshot_event_id, snapshot_payload, source_partition_id,
          source_offset_value, redis_snapshot_pending, updated_at_unix_ms
        ) KEY(venue_mic, symbol) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        values);
  }

  List<MarketDataSnapshotCacheEntry> pendingRedisSnapshots(int limit) {
    MarketDataStoreValidation.requirePositiveLimit(limit);
    return jdbcTemplate.query(
        """
        SELECT venue_mic, symbol, snapshot_event_id, snapshot_payload
        FROM market_data_projection.instrument_market_data
        WHERE redis_snapshot_pending = TRUE
        ORDER BY updated_at_unix_ms, venue_mic, symbol
        LIMIT ?
        """,
        (resultSet, ignored) ->
            new MarketDataSnapshotCacheEntry(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getBytes(3),
                resultSet.getBytes(4)),
        limit);
  }

  void markRedisSnapshotCached(MarketDataSnapshotCacheEntry entry) {
    jdbcTemplate.update(
        """
        UPDATE market_data_projection.instrument_market_data
        SET redis_snapshot_pending = FALSE
        WHERE venue_mic = ? AND symbol = ? AND snapshot_event_id = ?
        """,
        entry.venueMic(),
        entry.symbol(),
        entry.eventId());
  }

  void reset() {
    jdbcTemplate.update("DELETE FROM market_data_projection.instrument_market_data");
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
