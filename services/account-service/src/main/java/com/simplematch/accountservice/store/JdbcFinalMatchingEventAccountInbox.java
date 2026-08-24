package com.simplematch.accountservice.store;

import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns Account's exact-byte final-event inbox and per-partition progress persistence. */
@Repository
public class JdbcFinalMatchingEventAccountInbox {
  private static final String CONSUMER_NAME = "account-final-matching-events";
  private final JdbcTemplate jdbcTemplate;

  /**
   * Creates the transaction-participating Account final-event inbox adapter.
   *
   * <p>The adapter owns its {@link JdbcTemplate} configuration while sharing the caller's
   * transaction-participating data source.
   */
  public JdbcFinalMatchingEventAccountInbox(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = new JdbcTemplate(jdbcTemplate.getDataSource());
  }

  /** Claims one exact final event or fails closed when the identity's bytes conflict. */
  public boolean claim(byte[] eventId, byte[] payloadSha256, long receivedAtUnixMs) {
    final byte[] identity = requireSha256(eventId, "eventId");
    final byte[] observedHash = requireSha256(payloadSha256, "payloadSha256");
    final List<byte[]> existing =
        jdbcTemplate.query(
            """
            SELECT payload_sha256
            FROM account_service.matching_event_inbox
            WHERE consumer_name = ? AND event_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getBytes("payload_sha256"),
            CONSUMER_NAME,
            identity);
    if (!existing.isEmpty()) {
      assertMatchingHash(identity, existing.getFirst(), observedHash);
      return false;
    }
    try {
      jdbcTemplate.update(
          """
          INSERT INTO account_service.matching_event_inbox (
            consumer_name, event_id, payload_sha256, received_at_unix_ms
          ) VALUES (?, ?, ?, ?)
          """,
          CONSUMER_NAME,
          identity,
          observedHash,
          receivedAtUnixMs);
      return true;
    } catch (DuplicateKeyException duplicate) {
      final byte[] racedHash =
          jdbcTemplate.queryForObject(
              """
              SELECT payload_sha256
              FROM account_service.matching_event_inbox
              WHERE consumer_name = ? AND event_id = ?
              """,
              byte[].class,
              CONSUMER_NAME,
              identity);
      assertMatchingHash(identity, racedHash, observedHash);
      return false;
    }
  }

  /** Advances local progress only as part of the caller's final-event transaction. */
  public void recordProgress(int kafkaPartition, long kafkaOffset, long observedAtUnixMs) {
    if (kafkaPartition < 0 || kafkaPartition > 14 || kafkaOffset < 0 || observedAtUnixMs < 0) {
      throw new IllegalArgumentException("Account final event Kafka position is invalid");
    }
    if (isPostgres()) {
      jdbcTemplate.update(
          """
          INSERT INTO account_service.matching_event_consumer_progress (
            consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
          ) VALUES (?, ?, ?, ?)
          ON CONFLICT (consumer_name, partition_id) DO UPDATE SET
            last_processed_offset = GREATEST(
                account_service.matching_event_consumer_progress.last_processed_offset,
                EXCLUDED.last_processed_offset
            ),
            updated_at_unix_ms = EXCLUDED.updated_at_unix_ms
          """,
          CONSUMER_NAME,
          kafkaPartition,
          kafkaOffset,
          observedAtUnixMs);
      return;
    }
    jdbcTemplate.update(
        """
        MERGE INTO account_service.matching_event_consumer_progress (
          consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
        ) KEY(consumer_name, partition_id) VALUES (?, ?, ?, ?)
        """,
        CONSUMER_NAME,
        kafkaPartition,
        kafkaOffset,
        observedAtUnixMs);
  }

  private byte[] requireSha256(byte[] value, String name) {
    if (value == null || value.length != 32) {
      throw new IllegalArgumentException(name + " must contain exactly 32 bytes");
    }
    return value.clone();
  }

  private void assertMatchingHash(byte[] eventId, byte[] existing, byte[] observed) {
    if (!Arrays.equals(existing, observed)) {
      throw new DeterministicEventConflictException(HexFormat.of().formatHex(eventId));
    }
  }

  private boolean isPostgres() {
    return Boolean.TRUE.equals(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    connection
                        .getMetaData()
                        .getDatabaseProductName()
                        .toLowerCase(Locale.ROOT)
                        .contains("postgresql")));
  }
}
