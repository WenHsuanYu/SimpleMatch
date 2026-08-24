package com.simplematch.persistence.store;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Coordinates final Matching fact storage behind one transaction-owned Persistence inbox boundary.
 *
 * <p>The caller's application transaction spans the inbox claim, immutable facts, order projection,
 * and consumer progress. Individual adapters expose only their own durable concern.
 */
@Repository
public class JdbcMatchingEventStore {
  private final MatchingEventInboxStore inboxStore;
  private final MatchingEventFactStore factStore;
  private final MatchingConsumerProgressStore progressStore;

  /** Creates the PostgreSQL/H2-compatible final Matching Event storage facade. */
  public JdbcMatchingEventStore(JdbcTemplate jdbcTemplate) {
    final JdbcMatchingOrderProjectionStore projectionStore =
        new JdbcMatchingOrderProjectionStore(jdbcTemplate);
    inboxStore = new MatchingEventInboxStore(jdbcTemplate);
    factStore = new MatchingEventFactStore(jdbcTemplate, projectionStore);
    progressStore = new MatchingConsumerProgressStore(jdbcTemplate);
  }

  /** Claims an event, persists every derived fact, and advances local partition progress. */
  public MatchingEventPersistenceOutcome persist(
      FinalMatchingEventEnvelope envelope,
      int kafkaPartition,
      long kafkaOffset,
      long receivedAtUnixMs) {
    final MatchingEventKafkaPosition position =
        new MatchingEventKafkaPosition(kafkaPartition, kafkaOffset, receivedAtUnixMs);
    final byte[] eventId = envelope.eventIdBytes();
    if (!inboxStore.claim(eventId, envelope.payloadSha256(), position.receivedAtUnixMs())) {
      progressStore.advance(position);
      return MatchingEventPersistenceOutcome.DUPLICATE;
    }
    factStore.persist(eventId, envelope.event());
    progressStore.advance(position);
    return MatchingEventPersistenceOutcome.APPLIED;
  }

  /** Returns durable last-processed offsets for the final-event consumer. */
  public Map<Integer, Long> loadLastProcessedOffsets() {
    return progressStore.loadLastProcessedOffsets();
  }
}
