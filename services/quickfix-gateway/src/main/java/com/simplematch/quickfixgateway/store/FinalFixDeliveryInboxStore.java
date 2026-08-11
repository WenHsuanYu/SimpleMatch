package com.simplematch.quickfixgateway.store;

import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL operations for Gateway exact-byte inbox evidence during critical final-event consumption. */
final class FinalFixDeliveryInboxStore {
  private static final String CONSUMER_NAME = "quickfix-final-matching-events";

  private FinalFixDeliveryInboxStore() {}

  static boolean claim(
      JdbcTemplate jdbcTemplate, FinalMatchingEventEnvelope envelope, long receivedAtUnixMs) {
    final byte[] eventId = envelope.eventIdBytes();
    final List<byte[]> existing =
        jdbcTemplate.query(
            """
            SELECT payload_sha256
            FROM quickfix_gateway.matching_event_inbox
            WHERE consumer_name = ? AND event_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getBytes("payload_sha256"),
            CONSUMER_NAME,
            eventId);
    if (!existing.isEmpty()) {
      assertMatchingHash(eventId, existing.getFirst(), envelope.payloadSha256());
      return false;
    }
    try {
      jdbcTemplate.update(
          """
          INSERT INTO quickfix_gateway.matching_event_inbox (
            consumer_name, event_id, payload_sha256, raw_payload, received_at_unix_ms
          ) VALUES (?, ?, ?, ?, ?)
          """,
          CONSUMER_NAME,
          eventId,
          envelope.payloadSha256(),
          envelope.rawValue(),
          receivedAtUnixMs);
      return true;
    } catch (DuplicateKeyException duplicate) {
      final byte[] racedHash =
          jdbcTemplate.queryForObject(
              """
              SELECT payload_sha256
              FROM quickfix_gateway.matching_event_inbox
              WHERE consumer_name = ? AND event_id = ?
              """,
              byte[].class,
              CONSUMER_NAME,
              eventId);
      assertMatchingHash(eventId, racedHash, envelope.payloadSha256());
      return false;
    }
  }

  private static void assertMatchingHash(byte[] eventId, byte[] existing, byte[] observed) {
    if (!Arrays.equals(existing, observed)) {
      throw new DeterministicEventConflictException(HexFormat.of().formatHex(eventId));
    }
  }
}
