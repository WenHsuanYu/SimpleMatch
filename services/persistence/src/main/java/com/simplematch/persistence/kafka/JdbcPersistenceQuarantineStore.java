package com.simplematch.persistence.kafka;

import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists exact raw-byte evidence before a critical Persistence partition remains paused. */
public final class JdbcPersistenceQuarantineStore implements QuarantineStore {
  private final JdbcTemplate jdbcTemplate;

  /** Creates the Persistence schema quarantine adapter. */
  public JdbcPersistenceQuarantineStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void save(QuarantineEvidence evidence) {
    final DeliveryPosition position = evidence.record().position();
    jdbcTemplate.update(
        """
        INSERT INTO persistence.matching_consumer_quarantines (
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
            UPDATE persistence.matching_consumer_quarantines
            SET status = 'RECOVERED', recovered_at_unix_ms = ?
            WHERE topic = ? AND partition_id = ? AND offset_value = ? AND status = 'QUARANTINED'
            """,
            recoveredAtUnixMs,
            position.topic(),
            position.partition(),
            position.offset())
        != 1) {
      throw new IllegalStateException("persistence quarantine recovery found no open record");
    }
  }

  private byte[] binaryEventIdOrNull(String eventId) {
    return eventId.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(eventId) : null;
  }
}
