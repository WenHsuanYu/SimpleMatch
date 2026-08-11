package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Verifies exact-byte fingerprinting and schema validation for every critical final-event consumer. */
class FinalMatchingEventEnvelopeTest {
  @Test
  void parsesTheCppGoldenRecordAndRetainsItsExactByteHash() throws IOException {
    final byte[] raw = fixture();

    final FinalMatchingEventEnvelope envelope = FinalMatchingEventEnvelope.parse(raw);

    assertEquals(32, envelope.eventIdBytes().length);
    assertEquals(64, envelope.payloadSha256Hex().length());
    assertEquals(raw.length, envelope.rawValue().length);
    assertArrayEquals(FinalMatchingEventEnvelope.sha256(raw), envelope.payloadSha256());
  }

  @Test
  void rejectsAnEventWhoseTypeDoesNotMatchItsPayload() throws IOException {
    final MatchingEvent event = MatchingEvent.parseFrom(fixture());
    final byte[] invalid =
        event
            .toBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(event.getOrderRested())
            .build()
            .toByteArray();

    assertThrows(IllegalArgumentException.class, () -> FinalMatchingEventEnvelope.parse(invalid));
  }

  private byte[] fixture() throws IOException {
    try (InputStream stream =
        getClass().getResourceAsStream("/native-routing-fixtures/cpp-matching-event-v1.hex")) {
      final String hex = new String(stream.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
      return HexFormat.of().parseHex(hex);
    }
  }
}
