package com.simplematch.quickfixgateway.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradingSystemStatusEvaluatorTest {
  private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");
  private final TradingSystemStatusEvaluator evaluator =
      new TradingSystemStatusEvaluator(
          15, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120));

  @Test
  void zeroMarketActivityIsOpenEligibleWithoutAnOldestEventAge() {
    final TradingSystemStatus status =
        evaluator.evaluate(TradingSystemStatusFixtures.readyObservation(NOW), NOW);

    assertThat(status.readiness()).isEqualTo(TradingReadiness.OPEN_ELIGIBLE);
    assertThat(status.reasons()).isEmpty();
    assertThat(status.warnings()).isEmpty();
  }

  @Test
  void staleComponentStatusRequiresANewOrderPause() {
    final TradingSystemStatus status =
        evaluator.evaluate(
            TradingSystemStatusFixtures.readyObservation(NOW.minusSeconds(6)), NOW);

    assertThat(status.readiness()).isEqualTo(TradingReadiness.PAUSE_REQUIRED);
    assertThat(status.reasons()).anyMatch(reason -> reason.contains("STALE"));
  }

  @Test
  void mismatchedMatchingArtifactInterruptsTheMarket() {
    final TradingSystemObservation observation =
        TradingSystemStatusFixtures.withMatchingIdentity(
            TradingSystemStatusFixtures.readyObservation(NOW),
            7,
            TradingSystemStatusFixtures.differentArtifactIdentity());

    final TradingSystemStatus status = evaluator.evaluate(observation, NOW);

    assertThat(status.readiness()).isEqualTo(TradingReadiness.INTERRUPT_REQUIRED);
    assertThat(status.reasons()).anyMatch(reason -> reason.contains("IDENTITY_MISMATCH"));
  }

  @Test
  void criticalOldestEventWarnsThenPausesAtTheConfiguredThreshold() {
    final TradingSystemStatus warning =
        evaluator.evaluate(
            TradingSystemStatusFixtures.withCriticalOldestAge(
                TradingSystemStatusFixtures.readyObservation(NOW),
                CriticalConsumer.PERSISTENCE,
                3,
                Duration.ofSeconds(30)),
            NOW);
    final TradingSystemStatus pause =
        evaluator.evaluate(
            TradingSystemStatusFixtures.withCriticalOldestAge(
                TradingSystemStatusFixtures.readyObservation(NOW),
                CriticalConsumer.PERSISTENCE,
                3,
                Duration.ofMinutes(2)),
            NOW);

    assertThat(warning.readiness()).isEqualTo(TradingReadiness.PAUSE_REQUIRED);
    assertThat(warning.warnings()).isNotEmpty();
    assertThat(pause.readiness()).isEqualTo(TradingReadiness.PAUSE_REQUIRED);
    assertThat(pause.reasons()).anyMatch(reason -> reason.contains("OLDEST_EVENT"));
  }

  @Test
  void deterministicEventIdentityConflictInterruptsTheMarket() {
    final TradingSystemStatus status =
        evaluator.evaluate(
            TradingSystemStatusFixtures.withKafkaEventIdentityConflict(
                TradingSystemStatusFixtures.readyObservation(NOW)),
            NOW);

    assertThat(status.readiness()).isEqualTo(TradingReadiness.INTERRUPT_REQUIRED);
    assertThat(status.reasons()).anyMatch(reason -> reason.contains("EVENT_ID_PAYLOAD_CONFLICT"));
  }
}
