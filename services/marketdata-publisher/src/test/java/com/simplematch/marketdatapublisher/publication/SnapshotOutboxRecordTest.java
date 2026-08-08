package com.simplematch.marketdatapublisher.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotOutboxRecordTest {
  @DisplayName("outbox payload owns bytes at construction and read boundaries")
  @Test
  void payloadIsDefensivelyCopied() {
    final byte[] input = new byte[] {1, 2, 3};
    final SnapshotOutboxRecord.Payload payload =
        new SnapshotOutboxRecord.Payload(input, "snapshot.v1", "{}");

    input[0] = 9;
    final byte[] exposed = payload.bytes();
    exposed[1] = 8;

    assertThat(payload.bytes()).containsExactly(1, 2, 3);
  }

  @DisplayName("outbox exposes event, destination, payload, aggregate, and time as groups")
  @Test
  void exposesSemanticGroups() {
    final UUID eventId = UUID.randomUUID();
    final SnapshotOutboxRecord record =
        new SnapshotOutboxRecord(
            new SnapshotOutboxRecord.EventIdentity(eventId),
            new SnapshotOutboxRecord.Destination("market-reference.snapshots", "2026-07-27"),
            new SnapshotOutboxRecord.Payload(new byte[] {1}, "snapshot.v1", "{}"),
            new SnapshotOutboxRecord.AggregateReference("market_snapshot", "snapshot-1"),
            100L);

    assertThat(record.eventIdentity().eventId()).isEqualTo(eventId);
    assertThat(record.destination().topic()).isEqualTo("market-reference.snapshots");
    assertThat(record.destination().messageKey()).isEqualTo("2026-07-27");
    assertThat(record.destination().kafkaPartitionId()).isNull();
    assertThat(record.payload().payloadType()).isEqualTo("snapshot.v1");
    assertThat(record.payload().headersJson()).isEqualTo("{}");
    assertThat(record.aggregateReference().aggregateType()).isEqualTo("market_snapshot");
    assertThat(record.aggregateReference().aggregateId()).isEqualTo("snapshot-1");
    assertThat(record.createdAtUnixMs()).isEqualTo(100L);
  }

  @DisplayName("outbox rejects invalid destination and publication timestamp values")
  @Test
  void rejectsInvalidValues() {
    assertThatThrownBy(() -> new SnapshotOutboxRecord.Destination(" ", "2026-07-27"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("topic is required");
    assertThatThrownBy(
            () -> new SnapshotOutboxRecord.Destination("market-reference.snapshots", "2026-07-27", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("kafka partition must be non-negative");
    assertThatThrownBy(
            () ->
                new SnapshotOutboxRecord(
                    new SnapshotOutboxRecord.EventIdentity(UUID.randomUUID()),
                    new SnapshotOutboxRecord.Destination(
                        "market-reference.snapshots", "2026-07-27"),
                    new SnapshotOutboxRecord.Payload(new byte[] {1}, "snapshot.v1", "{}"),
                    new SnapshotOutboxRecord.AggregateReference(
                        "market_snapshot", "snapshot-1"),
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("outbox timestamp must be positive");
  }
}
