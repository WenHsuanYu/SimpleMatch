package com.simplematch.marketdataprojection.kafka;

import com.simplematch.config.delivery.DeadLetterEvidence;
import com.simplematch.config.delivery.DeadLetterStore;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores non-critical delivery evidence durably before its source offset may be released. */
public final class JdbcMarketDataProjectionDeadLetterStore implements DeadLetterStore {
  private static final int MAX_REASON_LENGTH = 512;
  private final JdbcTemplate jdbcTemplate;

  /** Creates the projection-owned dead-letter evidence adapter. */
  public JdbcMarketDataProjectionDeadLetterStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void save(DeadLetterEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    final Object[] values = {
      evidence.consumerName(),
      evidence.record().eventId(),
      evidence.record().position().topic(),
      evidence.record().position().partition(),
      evidence.record().position().offset(),
      evidence.record().payload(),
      evidence.attempts(),
      truncate(evidence.reason()),
      evidence.deadLetteredAt().toEpochMilli()
    };
    if (isPostgres()) {
      jdbcTemplate.update(
          """
          INSERT INTO market_data_projection.matching_event_dead_letters (
            consumer_name, event_id, topic, partition_id, offset_value, payload, attempts,
            failure_reason, dead_lettered_at_unix_ms
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (consumer_name, topic, partition_id, offset_value) DO NOTHING
          """,
          values);
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO market_data_projection.matching_event_dead_letters (
          consumer_name, event_id, topic, partition_id, offset_value, payload, attempts,
          failure_reason, dead_lettered_at_unix_ms
        ) KEY(consumer_name, topic, partition_id, offset_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        values);
  }

  private String truncate(String value) {
    return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
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
