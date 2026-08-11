package com.simplematch.persistence.store;

import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores exact final-event identity evidence for Persistence's critical consumer. */
final class MatchingEventInboxStore {
  private static final String CONSUMER_NAME = "persistence-matching-events";
  private final JdbcTemplate jdbcTemplate;

  MatchingEventInboxStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  boolean claim(byte[] eventId, byte[] payloadSha256, long receivedAtUnixMs) {
    final List<byte[]> existing =
        jdbcTemplate.query(
            """
            SELECT payload_sha256
            FROM persistence.matching_event_inbox
            WHERE consumer_name = ? AND event_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getBytes("payload_sha256"),
            CONSUMER_NAME,
            eventId);
    if (!existing.isEmpty()) {
      assertMatchingHash(eventId, existing.getFirst(), payloadSha256);
      return false;
    }
    try {
      jdbcTemplate.update(
          """
          INSERT INTO persistence.matching_event_inbox (
            consumer_name, event_id, payload_sha256, received_at_unix_ms
          ) VALUES (?, ?, ?, ?)
          """,
          CONSUMER_NAME,
          eventId,
          payloadSha256,
          receivedAtUnixMs);
      return true;
    } catch (DuplicateKeyException duplicate) {
      final byte[] racedHash =
          jdbcTemplate.queryForObject(
              """
              SELECT payload_sha256
              FROM persistence.matching_event_inbox
              WHERE consumer_name = ? AND event_id = ?
              """,
              byte[].class,
              CONSUMER_NAME,
              eventId);
      assertMatchingHash(eventId, racedHash, payloadSha256);
      return false;
    }
  }

  private void assertMatchingHash(byte[] eventId, byte[] existing, byte[] observed) {
    if (!Arrays.equals(existing, observed)) {
      throw new DeterministicEventConflictException(HexFormat.of().formatHex(eventId));
    }
  }
}
