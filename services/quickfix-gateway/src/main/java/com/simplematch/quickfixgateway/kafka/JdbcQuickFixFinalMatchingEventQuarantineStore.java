package com.simplematch.quickfixgateway.kafka;

import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists exact final-event evidence before the Gateway leaves a critical partition stopped. */
public final class JdbcQuickFixFinalMatchingEventQuarantineStore implements QuarantineStore {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the Gateway-local final-event quarantine adapter. */
  public JdbcQuickFixFinalMatchingEventQuarantineStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void save(QuarantineEvidence evidence) {
    final DeliveryPosition position = evidence.record().position();
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.matching_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, event_id, payload_sha256, reason, status,
          quarantined_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUARANTINED', ?)
        """,
        evidence.consumerName(),
        position.topic(),
        position.partition(),
        position.offset(),
        binaryEventIdOrNull(evidence.record().eventId()),
        FinalMatchingEventEnvelope.sha256(evidence.record().payload()),
        evidence.reason(),
        evidence.quarantinedAt().toEpochMilli());
  }

  @Override
  public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
    if (jdbcTemplate.update(
            """
            UPDATE quickfix_gateway.matching_consumer_quarantines
            SET status = 'RECOVERED', recovered_at_unix_ms = ?
            WHERE topic = ? AND partition_id = ? AND offset_value = ? AND status = 'QUARANTINED'
            """,
            recoveredAtUnixMs,
            position.topic(),
            position.partition(),
            position.offset())
        != 1) {
      throw new IllegalStateException(
          "Gateway final-event quarantine recovery found no open record");
    }
  }

  private byte[] binaryEventIdOrNull(String eventId) {
    return eventId.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(eventId) : null;
  }
}
