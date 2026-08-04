package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.routing.v2.InstrumentRoutingAssignment;
import com.simplematch.contracts.routing.v2.RoutingPolicy;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingPolicyContractTest {
  @Test
  @DisplayName("routing policy fixture round-trips as one complete immutable publication")
  void routingPolicyFixtureRoundTrips() throws Exception {
    final RoutingPolicy fixture =
        RoutingPolicy.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v2")
                    .setEventId(UUID.randomUUID().toString())
                    .setCreatedAtUnixMs(1_735_689_600_000L)
                    .setSourceService("marketdata-publisher")
                    .build())
            .setRoutingPolicyId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01")
            .setSourceMarketSnapshotId("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001")
            .setTradingDay(TradingDay.newBuilder().setIsoDate("2025-01-02").build())
            .setEffectiveFromUnixMs(1_735_689_600_000L)
            .setEffectiveUntilUnixMs(1_735_725_600_000L)
            .setOrdersValidatedPartitionCount(16)
            .addAssignments(
                InstrumentRoutingAssignment.newBuilder()
                    .setInstrument(
                        VenueInstrument.newBuilder()
                            .setSymbol("2330")
                            .setVenueMic("XTAI")
                            .build())
                    .setRoutingPartition(7)
                    .build())
            .build();

    final RoutingPolicy decoded = RoutingPolicy.parseFrom(ByteString.copyFrom(fixture.toByteArray()));

    assertEquals(fixture, decoded);
    assertEquals("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01", decoded.getRoutingPolicyId());
    assertEquals(16, decoded.getOrdersValidatedPartitionCount());
    assertEquals(7, decoded.getAssignments(0).getRoutingPartition());
  }
}
