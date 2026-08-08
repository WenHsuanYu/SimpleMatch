package com.simplematch.riskservice.routing;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class RoutingPolicyProjectionConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void commitsOnlyAfterProjectionSucceeds() {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<Object, Object> consumer = mockConsumer();
    final RoutingPolicyProjectionConsumer projectionConsumer =
        new RoutingPolicyProjectionConsumer(
            payload ->
                new RoutingPolicyProjectionResult(
                    UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c02"), false),
            controller(new RecordingQuarantineStore(), 2));
    final ConsumerRecord<String, byte[]> record = record(10L);

    projectionConsumer.onPolicy(record, acknowledgment, consumer);

    verify(acknowledgment).acknowledge();
    verify(consumer, never()).seek(new TopicPartition("routing", 3), 10L);
  }

  @Test
  void retriesInPlaceThenPausesAndDurablyRecordsThePoisonOffset() {
    final RecordingQuarantineStore store = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<Object, Object> consumer = mockConsumer();
    final RoutingPolicyProjectionConsumer projectionConsumer =
        new RoutingPolicyProjectionConsumer(
            payload -> {
              throw new IllegalArgumentException("incompatible policy");
            },
            controller(store, 2));
    final ConsumerRecord<String, byte[]> record = record(10L);
    final TopicPartition partition = new TopicPartition("routing", 3);

    projectionConsumer.onPolicy(record, acknowledgment, consumer);
    projectionConsumer.onPolicy(record, acknowledgment, consumer);

    verify(consumer).seek(partition, 10L);
    verify(consumer).pause(List.of(partition));
    verify(acknowledgment, never()).acknowledge();
    assertThat(store.evidence).singleElement().satisfies(evidence -> {
      assertThat(evidence.record().position().offset()).isEqualTo(10L);
      assertThat(evidence.retryHistory()).hasSize(2);
    });

    projectionConsumer.resume(new DeliveryPosition("routing", 3, 10L), 123L);
    assertThat(store.recovered).containsExactly(10L);
    assertThat(store.evidence).allSatisfy(evidence ->
        assertThat(evidence.consumerName()).isEqualTo("risk-routing-policy"));
  }

  private CriticalDeliveryController controller(RecordingQuarantineStore store, int attempts) {
    return new CriticalDeliveryController(
        "risk-routing-policy",
        attempts,
        "Correct the policy, then resume the same topic partition and offset.",
        CLOCK,
        store);
  }

  @SuppressWarnings("unchecked")
  private Consumer<Object, Object> mockConsumer() {
    return (Consumer<Object, Object>) mock(Consumer.class);
  }

  private ConsumerRecord<String, byte[]> record(long offset) {
    return new ConsumerRecord<>("routing", 3, offset, "policy-1", new byte[] {1, 2, 3});
  }

  private static final class RecordingQuarantineStore implements QuarantineStore {
    private final List<QuarantineEvidence> evidence = new ArrayList<>();
    private final List<Long> recovered = new ArrayList<>();

    @Override
    public void save(QuarantineEvidence evidence) {
      this.evidence.add(evidence);
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
      recovered.add(position.offset());
    }
  }
}
