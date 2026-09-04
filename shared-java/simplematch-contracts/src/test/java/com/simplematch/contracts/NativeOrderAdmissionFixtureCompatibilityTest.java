package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NativeOrderAdmissionFixtureCompatibilityTest {
  @DisplayName("native fixtures are serialized by the generated Java contracts")
  @Test
  void generatedJavaContractsMatchNativeFixtures() throws IOException {
    assertArrayEquals(
        acceptedOrder().toByteArray(),
        fixture("java-order-admission-accepted-v2.hex"));
  }

  private OrderAdmissionAccepted acceptedOrder() {
    return OrderAdmissionAccepted.newBuilder()
        .setCommandId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c03")
        .setOrderId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c04")
        .setAccountId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c05")
        .setInstrument(
            VenueInstrument.newBuilder().setSymbol("2330").setVenueMic("XTAI"))
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
