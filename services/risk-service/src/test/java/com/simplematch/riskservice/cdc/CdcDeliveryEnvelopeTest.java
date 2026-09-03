package com.simplematch.riskservice.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CdcDeliveryEnvelopeTest {
  private static final UUID EVENT_ID =
      UUID.fromString("01990f4a-ff80-7c2c-b71c-33caa9b271d2");

  @Test
  @DisplayName("owns payload bytes across construction and access")
  void ownsPayloadBytesAcrossConstructionAndAccess() {
    final byte[] sourcePayload = {1, 2, 3};
    final CdcDeliveryEnvelope envelope =
        new CdcDeliveryEnvelope(
            EVENT_ID, "key", sourcePayload, "event.type", "{}", 1_788_307_200_000L);

    sourcePayload[0] = 9;
    final byte[] returnedPayload = envelope.payload();
    returnedPayload[1] = 8;

    assertThat(envelope.payload()).containsExactly(1, 2, 3);
  }
}
