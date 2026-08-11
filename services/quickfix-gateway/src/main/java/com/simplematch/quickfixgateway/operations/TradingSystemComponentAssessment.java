package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Applies reusable component identity, liveness, position, and backlog rules. */
final class TradingSystemComponentAssessment {
  private final TradingSystemReadinessThresholds thresholds;

  TradingSystemComponentAssessment(TradingSystemReadinessThresholds thresholds) {
    this.thresholds = thresholds;
  }

  void assessRisk(RiskStatus status, TradingSystemAssessment assessment) {
    assessComponentState("RISK", status.state(), assessment);
    assessFreshness("RISK", status.observedAt(), assessment);
  }

  void assessKafka(KafkaStatus status, TradingSystemAssessment assessment) {
    assessIdentity("KAFKA", status.identity(), assessment);
    assessComponentState("KAFKA", status.state(), assessment);
    assessFreshness("KAFKA", status.observedAt(), assessment);
    if (status.commandPartitionCount() != thresholds.expectedPartitionCount()) {
      assessment.interrupt("KAFKA_COMMAND_TOPIC_TOPOLOGY_MISMATCH");
    }
    if (status.eventPartitionCount() != thresholds.expectedPartitionCount()) {
      assessment.interrupt("KAFKA_EVENT_TOPIC_TOPOLOGY_MISMATCH");
    }
    if (status.sameEventIdDifferentPayload()) {
      assessment.interrupt("EVENT_ID_PAYLOAD_CONFLICT");
    }
  }

  void assessIdentity(
      String component, TradingIdentity identity, TradingSystemAssessment assessment) {
    if (!assessment.canonicalIdentity().equals(identity)) {
      assessment.interrupt(component + "_IDENTITY_MISMATCH");
    }
  }

  void assessComponentState(
      String component, OperationalComponentState state, TradingSystemAssessment assessment) {
    if (state == OperationalComponentState.QUARANTINED) {
      assessment.interrupt(component + "_QUARANTINED");
    } else if (state != OperationalComponentState.READY) {
      assessment.pause(component + "_" + state);
    }
  }

  void assessFreshness(String component, Instant observedAt, TradingSystemAssessment assessment) {
    if (Duration.between(observedAt, assessment.now()).compareTo(thresholds.staleStatusAfter())
        > 0) {
      assessment.pause(component + "_STATUS_STALE");
    }
  }

  void assessOffsets(
      String component, long committedOffset, long endOffset, TradingSystemAssessment assessment) {
    if (committedOffset > endOffset) {
      assessment.interrupt(component + "_OFFSET_CONTRADICTION");
    } else if (committedOffset < endOffset) {
      assessment.pause(component + "_LAGGING");
    }
  }

  void assessOldestUnprocessedAge(
      String component,
      Optional<Duration> oldestUnprocessedAge,
      TradingSystemAssessment assessment) {
    oldestUnprocessedAge.ifPresent(
        age -> {
          if (age.compareTo(thresholds.warningOldestEventAfter()) >= 0) {
            assessment.warn(component + "_OLDEST_EVENT_WARNING");
          }
          if (age.compareTo(thresholds.pauseOldestEventAfter()) >= 0) {
            assessment.pause(component + "_OLDEST_EVENT_EXCEEDED");
          }
        });
  }
}
