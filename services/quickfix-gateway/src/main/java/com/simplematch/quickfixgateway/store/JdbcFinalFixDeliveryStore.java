package com.simplematch.quickfixgateway.store;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIntent;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Coordinates the Gateway's final-event inbox, delivery ledger, and consumer progress adapters.
 *
 * <p>The application service owns the transaction that composes these adapters. This facade keeps
 * the existing Gateway boundary while each collaborator owns one durable concern.
 */
public class JdbcFinalFixDeliveryStore {
  private final JdbcTemplate jdbcTemplate;

  /**
   * Creates the Gateway-owned final-event persistence facade.
   *
   * <p>The facade owns its JDBC template configuration while sharing the caller's
   * transaction-participating data source.
   */
  public JdbcFinalFixDeliveryStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = new JdbcTemplate(jdbcTemplate.getDataSource());
  }

  /** Claims exact raw final-event bytes or detects a conflicting reuse of the event identity. */
  public boolean claimInbox(FinalMatchingEventEnvelope envelope, long receivedAtUnixMs) {
    return FinalFixDeliveryInboxStore.claim(jdbcTemplate, envelope, receivedAtUnixMs);
  }

  /** Records all client report intents belonging to an already claimed final Matching Event. */
  public void recordDeliveryIntents(List<FinalFixDeliveryIntent> intents) {
    FinalFixDeliveryIntentStore.insertAll(jdbcTemplate, intents);
  }

  /** Advances Gateway-local final-event progress after the complete transaction is durable. */
  public void advanceProgress(int kafkaPartition, long kafkaOffset, long observedAtUnixMs) {
    FinalFixDeliveryProgressStore.advance(
        jdbcTemplate,
        new FinalFixDeliveryKafkaPosition(kafkaPartition, kafkaOffset, observedAtUnixMs));
  }

  /** Returns a deterministic bounded batch of delivery intents that have not been socket-sent. */
  public List<FinalFixDeliveryIntent> findPending(int maximumBatchSize) {
    return FinalFixDeliveryIntentStore.findPending(jdbcTemplate, maximumBatchSize);
  }

  /** Marks one intent sent only after QuickFIX/J has accepted its at-least-once socket delivery. */
  public boolean markSent(String deliveryId, long sentAtUnixMs) {
    return FinalFixDeliveryIntentStore.markSent(jdbcTemplate, deliveryId, sentAtUnixMs);
  }

  /** Returns the oldest unsent intent time, or {@code null} when an idle market has no work. */
  public Long oldestPendingCreatedAtUnixMs() {
    return FinalFixDeliveryIntentStore.oldestPendingCreatedAtUnixMs(jdbcTemplate);
  }

  /** Returns the number of durable but not-yet-socket-sent report intents. */
  public long pendingIntentCount() {
    return FinalFixDeliveryIntentStore.pendingIntentCount(jdbcTemplate);
  }
}
