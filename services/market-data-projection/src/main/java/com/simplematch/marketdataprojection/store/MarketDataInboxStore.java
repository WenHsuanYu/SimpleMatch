package com.simplematch.marketdataprojection.store;

import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores exact final-event payload identity evidence for the rebuildable market-data consumer. */
final class MarketDataInboxStore {
  private final JdbcTemplate jdbcTemplate;

  MarketDataInboxStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  boolean isDuplicate(FinalMatchingEventEnvelope envelope) {
    final Optional<byte[]> existingHash = findHash(envelope.eventIdBytes());
    if (existingHash.isEmpty()) {
      return false;
    }
    assertMatchingHash(envelope.eventIdBytes(), existingHash.get(), envelope.payloadSha256());
    return true;
  }

  void insert(FinalMatchingEventEnvelope envelope, MarketDataProjectionPosition position) {
    try {
      jdbcTemplate.update(
          """
          INSERT INTO market_data_projection.matching_event_inbox (
            event_id, payload_sha256, partition_id, offset_value, received_at_unix_ms
          ) VALUES (?, ?, ?, ?, ?)
          """,
          envelope.eventIdBytes(),
          envelope.payloadSha256(),
          position.partition(),
          position.offset(),
          position.observedAtUnixMs());
    } catch (DuplicateKeyException duplicate) {
      final byte[] existing = findHash(envelope.eventIdBytes()).orElseThrow(() -> duplicate);
      assertMatchingHash(envelope.eventIdBytes(), existing, envelope.payloadSha256());
      throw new IllegalStateException("matching event inbox was concurrently replayed", duplicate);
    }
  }

  void reset() {
    jdbcTemplate.update("DELETE FROM market_data_projection.matching_event_inbox");
  }

  private Optional<byte[]> findHash(byte[] eventId) {
    return jdbcTemplate
        .query(
            "SELECT payload_sha256 FROM market_data_projection.matching_event_inbox WHERE event_id"
                + " = ?",
            (resultSet, ignored) -> resultSet.getBytes(1),
            eventId)
        .stream()
        .findFirst();
  }

  private void assertMatchingHash(byte[] eventId, byte[] existing, byte[] observed) {
    if (!Arrays.equals(existing, observed)) {
      throw new DeterministicEventConflictException(HexFormat.of().formatHex(eventId));
    }
  }
}
