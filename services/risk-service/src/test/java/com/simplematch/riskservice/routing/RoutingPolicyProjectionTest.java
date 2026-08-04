package com.simplematch.riskservice.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.routing.v2.InstrumentRoutingAssignment;
import com.simplematch.contracts.routing.v2.RoutingPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingPolicyProjectionTest {
  private static final UUID POLICY_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01");
  private static final UUID SNAPSHOT_ID =
      UUID.fromString("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001");

  @DisplayName("decoder validates the complete publication and preserves policy provenance")
  @Test
  void decodesCompletePolicy() {
    final RoutingPolicyProjection projection =
        RoutingPolicyProjectionDecoder.decode(fixture().toByteArray());

    assertThat(projection.identity().routingPolicyId()).isEqualTo(POLICY_ID);
    assertThat(projection.identity().sourceMarketSnapshotId()).isEqualTo(SNAPSHOT_ID);
    assertThat(projection.identity().tradingDay()).isEqualTo(LocalDate.of(2026, 7, 27));
    assertThat(projection.assignments()).hasSize(2);
    assertThat(projection.partitionFor(new RoutingInstrument("aapl", "xtai"))).isEqualTo(7);
    assertThat(projection.resolve(new RoutingInstrument("AAPL", "XTAI")))
        .isEqualTo(new RoutingPolicyResolution(POLICY_ID, 7));
    assertThat(projection.appliesAt(Instant.parse("2026-07-27T01:00:00Z"))).isTrue();
    assertThat(projection.appliesAt(Instant.parse("2026-07-27T06:00:00Z"))).isFalse();
  }

  @DisplayName("decoder rejects duplicate normalized instruments")
  @Test
  void rejectsDuplicateInstruments() {
    final RoutingPolicy duplicate =
        fixture().toBuilder()
            .clearAssignments()
            .addAssignments(assignment("AAPL", "XTAI", 7))
            .addAssignments(assignment("aapl", "xtai", 8))
            .build();

    assertThatThrownBy(() -> RoutingPolicyProjectionDecoder.decode(duplicate.toByteArray()))
        .isInstanceOf(RoutingPolicyProjectionValidationException.class)
        .hasMessageContaining("duplicate");
  }

  @DisplayName("decoder rejects partitions outside the declared topology")
  @Test
  void rejectsOutOfRangePartition() {
    final RoutingPolicy invalid =
        fixture().toBuilder().clearAssignments().addAssignments(assignment("AAPL", "XTAI", 16)).build();

    assertThatThrownBy(() -> RoutingPolicyProjectionDecoder.decode(invalid.toByteArray()))
        .isInstanceOf(RoutingPolicyProjectionValidationException.class)
        .hasMessageContaining("partition");
  }

  @DisplayName("decoder rejects a publication without strict event metadata")
  @Test
  void rejectsMissingMetadata() {
    final RoutingPolicy invalid = fixture().toBuilder().clearMetadata().build();

    assertThatThrownBy(() -> RoutingPolicyProjectionDecoder.decode(invalid.toByteArray()))
        .isInstanceOf(RoutingPolicyProjectionValidationException.class)
        .hasMessageContaining("metadata");
  }

  private RoutingPolicy fixture() {
    return RoutingPolicy.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c02")
                .setCreatedAtUnixMs(1_753_176_000_000L)
                .setSourceService("marketdata-publisher")
                .setCorrelationId(POLICY_ID.toString())
                .build())
        .setRoutingPolicyId(POLICY_ID.toString())
        .setSourceMarketSnapshotId(SNAPSHOT_ID.toString())
        .setTradingDay(TradingDay.newBuilder().setIsoDate("2026-07-27").build())
        .setEffectiveFromUnixMs(Instant.parse("2026-07-27T00:00:00Z").toEpochMilli())
        .setEffectiveUntilUnixMs(Instant.parse("2026-07-27T06:00:00Z").toEpochMilli())
        .setOrdersValidatedPartitionCount(16)
        .addAssignments(assignment("TSLA", "ROCO", 11))
        .addAssignments(assignment("AAPL", "XTAI", 7))
        .build();
  }

  private InstrumentRoutingAssignment assignment(String symbol, String venueMic, int partition) {
    return InstrumentRoutingAssignment.newBuilder()
        .setInstrument(
            VenueInstrument.newBuilder().setSymbol(symbol).setVenueMic(venueMic).build())
        .setRoutingPartition(partition)
        .build();
  }
}
