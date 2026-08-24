package com.simplematch.accountservice.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Verifies the operational status exposed by Account's critical Matching Event consumer. */
class AccountFinalMatchingEventOperationalStatusTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:10Z"), ZoneOffset.UTC);

  @Test
  void retryingRecordExposesOldestUnprocessedAge() {
    final AccountFinalMatchingEventStatus status = new AccountFinalMatchingEventStatus();
    final FinalMatchingEventAccountConsumer consumer =
        new FinalMatchingEventAccountConsumer(
            (command, partition, offset) -> FinalMatchingEventAccountOutcome.APPLIED,
            new CriticalDeliveryController(
                "account-final-matching-events",
                2,
                "Correct Account Authority, then resume the same topic partition and offset.",
                CLOCK,
                new NoopQuarantineStore()),
            status);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mock(Consumer.class);

    consumer.onMatchingEvent(
        new ConsumerRecord<>("matching.events", 0, 42L, new byte[32], new byte[] {1}),
        acknowledgment,
        kafkaConsumer);

    final Object pendingAges =
        new AccountFinalMatchingEventsHealthIndicator(status)
            .health()
            .getDetails()
            .get("oldestUnprocessedAgeMillis");
    assertThat(pendingAges).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) pendingAges).containsKey(0)).isTrue();
    verify(kafkaConsumer).seek(new TopicPartition("matching.events", 0), 42L);
    verify(acknowledgment, never()).acknowledge();
  }

  private static final class NoopQuarantineStore implements QuarantineStore {
    @Override
    public void save(QuarantineEvidence evidence) {
      // The first failed attempt retries in place and does not quarantine.
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
      // This tracer bullet does not exercise operator recovery.
    }
  }
}
