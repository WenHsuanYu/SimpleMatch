package com.simplematch.quickfixgateway.matching;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIntent;
import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Owns the Gateway-local atomic outcome of final-event inbox, intents, and progress. */
public class FinalMatchingEventFixDeliveryApplicationService
    implements FinalMatchingEventFixDeliveryHandler {
  private final JdbcFinalFixDeliveryStore store;
  private final FinalMatchingEventFixDeliveryPlanner planner;
  private final Clock clock;

  /** Creates the application service with persistence, planning, and UTC-time collaborators. */
  public FinalMatchingEventFixDeliveryApplicationService(
      JdbcFinalFixDeliveryStore store, FinalMatchingEventFixDeliveryPlanner planner, Clock clock) {
    this.store = store;
    this.planner = planner;
    this.clock = clock;
  }

  /** Persists one final event and all its recipient report intents in one local transaction. */
  @Override
  @Transactional
  public FinalMatchingEventFixDeliveryOutcome persist(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset) {
    final long receivedAtUnixMs = clock.millis();
    if (!store.claimInbox(envelope, receivedAtUnixMs)) {
      store.advanceProgress(kafkaPartition, kafkaOffset, receivedAtUnixMs);
      return FinalMatchingEventFixDeliveryOutcome.DUPLICATE;
    }
    final List<FinalFixDeliveryIntent> intents =
        planner.plan(envelope, kafkaPartition, kafkaOffset, receivedAtUnixMs);
    store.recordDeliveryIntents(intents);
    store.advanceProgress(kafkaPartition, kafkaOffset, receivedAtUnixMs);
    return FinalMatchingEventFixDeliveryOutcome.APPLIED;
  }
}
