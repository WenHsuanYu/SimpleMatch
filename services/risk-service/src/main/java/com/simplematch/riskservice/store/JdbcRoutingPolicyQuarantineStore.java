package com.simplematch.riskservice.store;

import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists Risk critical-consumer quarantine evidence in the Risk-owned schema. */
public final class JdbcRoutingPolicyQuarantineStore implements QuarantineStore {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the store over Risk's datasource without retaining a caller-owned template. */
  public JdbcRoutingPolicyQuarantineStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate =
        new JdbcTemplate(
            Objects.requireNonNull(jdbcTemplate, "jdbcTemplate").getDataSource());
  }

  @Override
  public void save(QuarantineEvidence evidence) {
    final DeliveryPosition position = evidence.record().position();
    jdbcTemplate.update(
        """
        INSERT INTO risk_service.consumer_quarantines (
          consumer_name, event_id, topic, partition_id, offset_value, reason, retry_history,
          recovery_instructions, quarantined_at_unix_ms, status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUARANTINED')
        """,
        evidence.consumerName(),
        evidence.record().eventId(),
        position.topic(),
        position.partition(),
        position.offset(),
        evidence.reason(),
        evidence.retryHistoryText(),
        evidence.recoveryInstructions(),
        evidence.quarantinedAt().toEpochMilli());
  }

  @Override
  public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
    if (jdbcTemplate.update(
            """
            UPDATE risk_service.consumer_quarantines
            SET status = 'RECOVERED', recovered_at_unix_ms = ?
            WHERE topic = ? AND partition_id = ? AND offset_value = ? AND status = 'QUARANTINED'
            """,
            recoveredAtUnixMs,
            position.topic(),
            position.partition(),
            position.offset())
        != 1) {
      throw new IllegalStateException("risk quarantine recovery found no open record");
    }
  }
}
