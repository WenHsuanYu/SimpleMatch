package com.simplematch.quickfixgateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.delivery.DeadLetterEvidence;
import com.simplematch.config.delivery.DeadLetterStore;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.DeliveryRecord;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class NonCriticalExecutionProjectionConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void delayedRetryDoesNotBlockTheKafkaSourcePosition() {
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final RecordingRetryScheduler scheduler = new RecordingRetryScheduler();
    final AtomicInteger projections = new AtomicInteger();
    final NonCriticalExecutionProjectionConsumer consumer =
        consumer(
            2,
            deadLetters,
            scheduler,
            payload -> {
              if (projections.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary FIX session failure");
              }
            });

    consumer.onExecution(record(17L));

    assertThat(projections).hasValue(1);
    assertThat(scheduler.retries).hasSize(1);
    assertThat(deadLetters.records).isEmpty();

    scheduler.runNext();

    assertThat(projections).hasValue(2);
    assertThat(deadLetters.records).isEmpty();
  }

  @Test
  void exhaustedProjectionRetriesKeepDiagnosticsInTheDeadLetterPath() {
    final RecordingDeadLetterStore deadLetters = new RecordingDeadLetterStore();
    final RecordingRetryScheduler scheduler = new RecordingRetryScheduler();
    final NonCriticalExecutionProjectionConsumer consumer =
        consumer(
            2,
            deadLetters,
            scheduler,
            payload -> {
              throw new IllegalArgumentException("session context is unavailable");
            });

    consumer.onExecution(record(17L));
    scheduler.runNext();

    assertThat(scheduler.retries).isEmpty();
    assertThat(deadLetters.records).singleElement().satisfies(evidence -> {
      assertThat(evidence.record().eventId()).isEqualTo("event-17");
      assertThat(evidence.record().position())
          .isEqualTo(new DeliveryPosition("matching.executions", 2, 17L));
      assertThat(evidence.attempts()).isEqualTo(2);
      assertThat(evidence.reason()).isEqualTo("session context is unavailable");
    });
  }

  private NonCriticalExecutionProjectionConsumer consumer(
      int maximumAttempts,
      RecordingDeadLetterStore deadLetters,
      RecordingRetryScheduler scheduler,
      NonCriticalExecutionProjectionConsumer.ExecutionProjection projection) {
    return new NonCriticalExecutionProjectionConsumer(
        projection,
        new NonCriticalDeliveryController(
            "quickfix-execution-projection",
            maximumAttempts,
            java.time.Duration.ofSeconds(1),
            CLOCK,
            deadLetters),
        scheduler);
  }

  private ConsumerRecord<String, byte[]> record(long offset) {
    final byte[] payload =
        ExecutionEvent.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setEventId("event-" + offset)
                    .setSchemaVersion("v1")
                    .build())
            .setExecId("exec-" + offset)
            .setOrderId("order-1")
            .setSymbol("2330")
            .build()
            .toByteArray();
    return new ConsumerRecord<>("matching.executions", 2, offset, "2330", payload);
  }

  private static final class RecordingRetryScheduler implements NonCriticalRetryScheduler {
    private final List<Runnable> retries = new ArrayList<>();

    @Override
    public void schedule(DeliveryRecord record, Instant retryAt, Runnable retry) {
      retries.add(retry);
    }

    private void runNext() {
      retries.remove(0).run();
    }
  }

  private static final class RecordingDeadLetterStore implements DeadLetterStore {
    private final List<DeadLetterEvidence> records = new ArrayList<>();

    @Override
    public void save(DeadLetterEvidence evidence) {
      records.add(evidence);
    }
  }
}
