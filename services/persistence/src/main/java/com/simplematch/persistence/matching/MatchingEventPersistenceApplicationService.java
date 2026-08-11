package com.simplematch.persistence.matching;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.persistence.store.JdbcMatchingEventStore;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the all-local transaction for Persistence's final Matching Event effects. */
@Service
public class MatchingEventPersistenceApplicationService implements MatchingEventPersistenceHandler {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;
  private final JdbcMatchingEventStore store;
  private final Clock clock;

  /** Creates the service-owned transaction boundary. */
  public MatchingEventPersistenceApplicationService(JdbcMatchingEventStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * Claims the inbox and commits final trade facts, fills, projections, and progress atomically.
   */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public MatchingEventPersistenceOutcome persist(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset) {
    return store.persist(
        Objects.requireNonNull(envelope, "envelope"), kafkaPartition, kafkaOffset, clock.millis());
  }
}
