package com.simplematch.config.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NonCriticalDeliveryControllerTest {
  @Test
  void delaysFailuresWithoutBlockingTheOriginalPartition() {
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final NonCriticalDeliveryController controller =
        new NonCriticalDeliveryController(
            "quickfix-projection",
            2,
            Duration.ofSeconds(5),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            deadLetters);
    final DeliveryRecord record =
        new DeliveryRecord("event-1", new DeliveryPosition("executions", 2, 9), new byte[] {1});

    final var retry = controller.onFailure(record, new IllegalStateException("temporary"));
    assertThat(retry.decision()).isEqualTo(DeliveryDecision.COMMIT);
    assertThat(retry.retryAt()).isEqualTo(Instant.ofEpochSecond(5));
    assertThat(deadLetters.records).isEmpty();
    assertThat(controller.onSuccess(record)).isEqualTo(DeliveryDecision.COMMIT);
  }

  @Test
  void deadLettersAfterTheBoundedRetryBudget() {
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final NonCriticalDeliveryController controller =
        new NonCriticalDeliveryController(
            "marketdata-projection",
            1,
            Duration.ofSeconds(1),
            Clock.systemUTC(),
            deadLetters);
    final DeliveryRecord record =
        new DeliveryRecord("event-1", new DeliveryPosition("marketdata", 1, 4), new byte[] {1});

    assertThat(controller.onFailure(record, new IllegalArgumentException("invalid view")))
        .extracting(NonCriticalDeliveryController.NonCriticalDeliveryResult::decision)
        .isEqualTo(DeliveryDecision.COMMIT);
    assertThat(deadLetters.records).singleElement().satisfies(evidence -> {
      assertThat(evidence.consumerName()).isEqualTo("marketdata-projection");
      assertThat(evidence.attempts()).isEqualTo(1);
      assertThat(evidence.record().position().offset()).isEqualTo(4);
    });
  }

  @Test
  void recordsRetryAndDeadLetterMetrics() {
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final InMemoryDeliveryMetrics metrics = new InMemoryDeliveryMetrics();
    final NonCriticalDeliveryController controller =
        new NonCriticalDeliveryController(
            "marketdata-projection",
            2,
            Duration.ofSeconds(1),
            Clock.systemUTC(),
            deadLetters,
            metrics);
    final DeliveryRecord record =
        new DeliveryRecord("event-1", new DeliveryPosition("marketdata", 1, 4), new byte[] {1});

    controller.onFailure(record, new IllegalArgumentException("temporary"));
    controller.onFailure(record, new IllegalArgumentException("invalid view"));

    assertThat(metrics.count(DeliveryMetric.RETRY, "marketdata-projection", record.position()))
        .isEqualTo(1);
    assertThat(
            metrics.count(DeliveryMetric.DEAD_LETTER, "marketdata-projection", record.position()))
        .isEqualTo(1);
  }

  private static final class RecordingDeadLetterStore implements DeadLetterStore {
    private final List<DeadLetterEvidence> records = new ArrayList<>();

    @Override
    public void save(DeadLetterEvidence evidence) {
      records.add(evidence);
    }
  }
}
