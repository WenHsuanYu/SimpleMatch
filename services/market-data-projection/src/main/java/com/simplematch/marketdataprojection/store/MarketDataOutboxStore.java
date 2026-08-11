package com.simplematch.marketdataprojection.store;

import com.simplematch.marketdataprojection.kafka.MarketDataOutboxRecord;
import com.simplematch.marketdataprojection.runtime.MarketDataSnapshotView;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores replayable public-market-data publication facts independently from projection state. */
final class MarketDataOutboxStore {
  private final JdbcTemplate jdbcTemplate;
  private final String marketDataTopic;

  MarketDataOutboxStore(JdbcTemplate jdbcTemplate, String marketDataTopic) {
    this.jdbcTemplate = jdbcTemplate;
    this.marketDataTopic = marketDataTopic;
  }

  void insert(byte[] eventId, byte[] sourceEventId, MarketDataSnapshotView view, byte[] payload) {
    jdbcTemplate.update(
        """
        INSERT INTO market_data_projection.market_data_events_outbox (
          event_id, source_matching_event_id, message_key, topic, payload, created_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        eventId,
        sourceEventId,
        view.venueMic() + ":" + view.symbol(),
        marketDataTopic,
        payload,
        view.generatedAtUnixMs());
  }

  List<MarketDataOutboxRecord> pending(int limit) {
    MarketDataStoreValidation.requirePositiveLimit(limit);
    return jdbcTemplate.query(
        """
        SELECT event_id, topic, message_key, payload
        FROM market_data_projection.market_data_events_outbox
        WHERE published_at_unix_ms IS NULL
        ORDER BY created_at_unix_ms, event_id
        LIMIT ?
        """,
        (resultSet, ignored) ->
            new MarketDataOutboxRecord(
                resultSet.getBytes(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getBytes(4)),
        limit);
  }

  void markPublished(MarketDataOutboxRecord record, long publishedAtUnixMs) {
    if (publishedAtUnixMs < 0) {
      throw new IllegalArgumentException("outbox publication time must not be negative");
    }
    jdbcTemplate.update(
        """
        UPDATE market_data_projection.market_data_events_outbox
        SET published_at_unix_ms = ?
        WHERE event_id = ? AND published_at_unix_ms IS NULL
        """,
        publishedAtUnixMs,
        record.eventId());
  }

  void reset() {
    jdbcTemplate.update("DELETE FROM market_data_projection.market_data_events_outbox");
  }
}
