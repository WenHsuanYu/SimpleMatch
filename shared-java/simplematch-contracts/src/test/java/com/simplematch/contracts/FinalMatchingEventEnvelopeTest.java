package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventTransportValidator;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Verifies native raw-record interoperability and exact-byte fingerprinting. */
class FinalMatchingEventEnvelopeTest {
  @Test
  void parsesEveryNativeFinalEventAndPinsItsRawValueHash() throws IOException {
    assertFixture(
        "cpp-matching-order-rested-v1.hex",
        MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED,
        "d4cf05dfa07dec2f54b55db4c793a3866539bcb3b2a5b6554722dc0719fafd94");
    assertFixture(
        "cpp-matching-trade-executed-v1.hex",
        MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED,
        "f263bd42b276005f17556ea3c1fbe5a998c6c1d438521cc5feb1b5c147087a27");
    assertFixture(
        "cpp-matching-order-cancelled-v1.hex",
        MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED,
        "0c63b3b0e3e952d674fa53b604e2c590cde9216a3bb20348d4f669fa6a08b4dd");
    assertFixture(
        "cpp-matching-order-expired-v1.hex",
        MatchingEventType.MATCHING_EVENT_TYPE_ORDER_EXPIRED,
        "8362a116785571de011a8fe53734adabc97735e9fa899063ff10dff117bf6b7c");
  }

  @Test
  void validatesKafkaKeyAndPartitionFromTheValidatedEnvelope() throws IOException {
    final FinalMatchingEventEnvelope envelope =
        FinalMatchingEventEnvelope.parse(
            fixture("cpp-matching-trade-executed-v1.hex"));

    assertDoesNotThrow(
        () ->
            FinalMatchingEventTransportValidator.requireKafkaRecord(
                envelope.eventIdBytes(),
                envelope.event().getPartitionId(),
                envelope));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FinalMatchingEventTransportValidator.requireKafkaRecord(
                new byte[32],
                envelope.event().getPartitionId(),
                envelope));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FinalMatchingEventTransportValidator.requireKafkaRecord(
                envelope.eventIdBytes(),
                envelope.event().getPartitionId() + 1,
                envelope));
  }

  @Test
  void rejectsAnEventWhoseTypeDoesNotMatchItsPayload() throws IOException {
    final MatchingEvent event =
        MatchingEvent.parseFrom(
            fixture("cpp-matching-trade-executed-v1.hex"));
    final byte[] invalid =
        event
            .toBuilder()
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(event.getOrderRested())
            .build()
            .toByteArray();

    assertThrows(
        IllegalArgumentException.class,
        () -> FinalMatchingEventEnvelope.parse(invalid));
  }

  private void assertFixture(
      String name, MatchingEventType type, String payloadSha256)
      throws IOException {
    final byte[] raw = fixture(name);
    final FinalMatchingEventEnvelope envelope =
        FinalMatchingEventEnvelope.parse(raw);

    assertEquals(type, envelope.event().getEventType());
    assertEquals(32, envelope.eventIdBytes().length);
    assertEquals(payloadSha256, envelope.payloadSha256Hex());
    assertEquals(raw.length, envelope.rawValue().length);
    assertArrayEquals(
        FinalMatchingEventEnvelope.sha256(raw), envelope.payloadSha256());
  }

  private byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        getClass().getResourceAsStream("/native-routing-fixtures/" + name)) {
      if (stream == null) {
        throw new IOException(
            "missing native Matching Event fixture " + name);
      }
      final String encoded =
          new String(stream.readAllBytes(), StandardCharsets.US_ASCII)
              .replaceAll("\\s", "");
      return HexFormat.of().parseHex(encoded);
    }
  }
}
