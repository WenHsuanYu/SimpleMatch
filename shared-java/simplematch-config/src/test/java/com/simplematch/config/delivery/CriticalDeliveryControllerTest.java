package com.simplematch.config.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriticalDeliveryControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

  @Test
  void retriesSameOffsetAndQuarantinesOnlyThatPartition() {
    final RecordingQuarantineStore store = new RecordingQuarantineStore();
    final CriticalDeliveryController controller = controller(store, 2);
    final DeliveryRecord failed = record("event-1", "orders", 3, 10);
    final DeliveryRecord later = record("event-2", "orders", 3, 11);
    final DeliveryRecord otherPartition = record("event-3", "orders", 4, 0);

    assertThat(controller.onFailure(failed, new IllegalStateException("first")))
        .isEqualTo(DeliveryDecision.RETRY_IN_PLACE);
    assertThat(controller.onSuccess(later)).isEqualTo(DeliveryDecision.BLOCKED);
    assertThat(controller.onFailure(failed, new IllegalStateException("second")))
        .isEqualTo(DeliveryDecision.QUARANTINED);
    assertThat(controller.onSuccess(later)).isEqualTo(DeliveryDecision.BLOCKED);
    assertThat(controller.onSuccess(otherPartition)).isEqualTo(DeliveryDecision.COMMIT);
    assertThat(store.evidence).singleElement().satisfies(evidence -> {
      assertThat(evidence.record().position().offset()).isEqualTo(10);
      assertThat(evidence.retryHistory()).hasSize(2);
      assertThat(evidence.recoveryInstructions()).contains("same");
    });
  }

  @Test
  void recoveryMustTargetTheSameQuarantinedOffsetBeforeRetryCanCommit() {
    final RecordingQuarantineStore store = new RecordingQuarantineStore();
    final CriticalDeliveryController controller = controller(store, 1);
    final DeliveryRecord failed = record("event-1", "policy", 0, 7);

    assertThat(controller.onFailure(failed, new IllegalStateException("poison")))
        .isEqualTo(DeliveryDecision.QUARANTINED);
    assertThatThrownBy(
            () -> controller.resume(new DeliveryPosition("policy", 0, 8), NOW.toEpochMilli()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("recovery must target the quarantined offset");

    controller.resume(failed.position(), NOW.toEpochMilli());
    assertThat(controller.onSuccess(failed)).isEqualTo(DeliveryDecision.COMMIT);
    assertThat(store.recoveredOffsets).containsExactly(7L);
  }

  @Test
  void recordsRetryQuarantineAndDuplicateMetricsWithoutChangingDeliveryPolicy() {
    final RecordingQuarantineStore store = new RecordingQuarantineStore();
    final InMemoryDeliveryMetrics metrics = new InMemoryDeliveryMetrics();
    final CriticalDeliveryController controller =
        new CriticalDeliveryController(
            "risk-cdc-delivery",
            2,
            "Correct the policy, then resume the same partition and offset.",
            Clock.fixed(NOW, ZoneOffset.UTC),
            store,
            metrics);
    final DeliveryRecord record = record("event-1", "policy", 0, 7);

    assertThat(controller.onFailure(record, new IllegalStateException("first")))
        .isEqualTo(DeliveryDecision.RETRY_IN_PLACE);
    assertThat(controller.onFailure(record, new IllegalStateException("second")))
        .isEqualTo(DeliveryDecision.QUARANTINED);
    controller.recordDuplicate(record);

    assertThat(metrics.count(DeliveryMetric.RETRY, "risk-cdc-delivery", record.position()))
        .isEqualTo(1);
    assertThat(metrics.count(DeliveryMetric.QUARANTINE, "risk-cdc-delivery", record.position()))
        .isEqualTo(1);
    assertThat(metrics.count(DeliveryMetric.DUPLICATE, "risk-cdc-delivery", record.position()))
        .isEqualTo(1);
  }

  private CriticalDeliveryController controller(RecordingQuarantineStore store, int attempts) {
    return new CriticalDeliveryController(
        "risk-cdc-delivery",
        attempts,
        "Correct the policy, then resume the same partition and offset.",
        Clock.fixed(NOW, ZoneOffset.UTC),
        store);
  }

  private DeliveryRecord record(String eventId, String topic, int partition, long offset) {
    return new DeliveryRecord(eventId, new DeliveryPosition(topic, partition, offset), new byte[] {1});
  }

  private static final class RecordingQuarantineStore implements QuarantineStore {
    private final List<QuarantineEvidence> evidence = new ArrayList<>();
    private final List<Long> recoveredOffsets = new ArrayList<>();

    @Override
    public void save(QuarantineEvidence evidence) {
      this.evidence.add(evidence);
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
      recoveredOffsets.add(position.offset());
    }
  }
}
