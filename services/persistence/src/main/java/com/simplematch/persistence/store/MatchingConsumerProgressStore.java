package com.simplematch.persistence.store;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores Persistence's completed final-event input position for each Kafka partition. */
final class MatchingConsumerProgressStore {
  private static final String CONSUMER_NAME = "persistence-matching-events";
  private final JdbcTemplate jdbcTemplate;

  MatchingConsumerProgressStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  void advance(MatchingEventKafkaPosition position) {
    jdbcTemplate.update(
        """
        INSERT INTO persistence.matching_consumer_progress (
          consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
        ) VALUES (?, ?, ?, ?)
        ON CONFLICT (consumer_name, partition_id) DO UPDATE SET
          last_processed_offset = EXCLUDED.last_processed_offset,
          updated_at_unix_ms = EXCLUDED.updated_at_unix_ms
        """,
        CONSUMER_NAME,
        position.partition(),
        position.offset(),
        position.receivedAtUnixMs());
  }

  Map<Integer, Long> loadLastProcessedOffsets() {
    return jdbcTemplate.query(
        """
        SELECT partition_id, last_processed_offset
        FROM persistence.matching_consumer_progress
        WHERE consumer_name = ?
        ORDER BY partition_id
        """,
        resultSet -> {
          final Map<Integer, Long> offsets = new HashMap<>();
          while (resultSet.next()) {
            offsets.put(
                resultSet.getInt("partition_id"), resultSet.getLong("last_processed_offset"));
          }
          return Map.copyOf(offsets);
        },
        CONSUMER_NAME);
  }
}
