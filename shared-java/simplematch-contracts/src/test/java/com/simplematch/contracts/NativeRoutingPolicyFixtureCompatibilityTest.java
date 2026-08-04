package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.routing.v2.InstrumentRoutingAssignment;
import com.simplematch.contracts.routing.v2.RoutingPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NativeRoutingPolicyFixtureCompatibilityTest {
  private static final String POLICY_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01";

  @DisplayName("native fixtures are serialized by the generated Java contracts")
  @Test
  void generatedJavaContractsMatchNativeFixtures() throws IOException {
    assertArrayEquals(
        policy().toByteArray(), fixture("java-routing-policy-v2.hex"));
    assertArrayEquals(
        acceptedOrder().toByteArray(),
        fixture("java-order-admission-accepted-v2.hex"));
  }

  private RoutingPolicy policy() {
    final EventMetadata metadata =
        EventMetadata.newBuilder()
            .setSchemaVersion("v2")
            .setEventId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c02")
            .setCreatedAtUnixMs(1_753_176_000_000L)
            .setSourceService("marketdata-publisher")
            .setCorrelationId(POLICY_ID)
            .build();
    return RoutingPolicy.newBuilder()
        .setMetadata(metadata)
        .setRoutingPolicyId(POLICY_ID)
        .setSourceMarketSnapshotId("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001")
        .setTradingDay(TradingDay.newBuilder().setIsoDate("2026-07-27"))
        .setEffectiveFromUnixMs(1_753_171_200_000L)
        .setEffectiveUntilUnixMs(1_753_192_800_000L)
        .setOrdersValidatedPartitionCount(16)
        .addAssignments(
            InstrumentRoutingAssignment.newBuilder()
                .setInstrument(
                    VenueInstrument.newBuilder().setSymbol("AAPL").setVenueMic("XTAI"))
                .setRoutingPartition(7))
        .build();
  }

  private OrderAdmissionAccepted acceptedOrder() {
    return OrderAdmissionAccepted.newBuilder()
        .setCommandId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c03")
        .setOrderId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c04")
        .setAccountId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c05")
        .setInstrument(
            VenueInstrument.newBuilder().setSymbol("aapl").setVenueMic("xtai"))
        .setRoutingPolicyId(POLICY_ID)
        .setRoutingPartition(7)
        .build();
  }

  private byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        getClass().getResourceAsStream("/native-routing-fixtures/" + name)) {
      assertNotNull(stream, "missing native fixture: " + name);
      return HexFormat.of()
          .parseHex(new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
  }
}
