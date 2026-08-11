package com.simplematch.quickfixgateway.store;

import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL operations for completed final-event input positions of the Gateway critical consumer. */
final class FinalFixDeliveryProgressStore {
  private static final String CONSUMER_NAME = "quickfix-final-matching-events";

  private FinalFixDeliveryProgressStore() {}

  static void advance(JdbcTemplate jdbcTemplate, FinalFixDeliveryKafkaPosition position) {
    if (isPostgres(jdbcTemplate)) {
      jdbcTemplate.update(
          """
          INSERT INTO quickfix_gateway.matching_consumer_progress (
            consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
          ) VALUES (?, ?, ?, ?)
          ON CONFLICT (consumer_name, partition_id) DO UPDATE SET
            last_processed_offset = EXCLUDED.last_processed_offset,
            updated_at_unix_ms = EXCLUDED.updated_at_unix_ms
          """,
          CONSUMER_NAME,
          position.partition(),
          position.offset(),
          position.observedAtUnixMs());
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO quickfix_gateway.matching_consumer_progress (
          consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
        ) KEY(consumer_name, partition_id) VALUES (?, ?, ?, ?)
        """,
        CONSUMER_NAME,
        position.partition(),
        position.offset(),
        position.observedAtUnixMs());
  }

  private static boolean isPostgres(JdbcTemplate jdbcTemplate) {
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
