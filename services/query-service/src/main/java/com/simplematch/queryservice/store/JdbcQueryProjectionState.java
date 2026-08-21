package com.simplematch.queryservice.store;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

/** Owns inbox, contiguous checkpoint, and replay state for JDBC query projections. */
final class JdbcQueryProjectionState {
  private static final String READY = "READY";
  private static final String GAP_DETECTED = "GAP_DETECTED";

  private JdbcQueryProjectionState() {}

  static boolean claimInbox(
      JdbcOperations jdbcTemplate,
      String eventId,
      String sourceTopic,
      byte[] payloadSha256,
      QueryProjectionPosition position) {
    final Optional<Inbox.Entry> existing = Inbox.find(jdbcTemplate, eventId);
    if (existing.isPresent()) {
      Inbox.acceptEquivalentDuplicate(
          jdbcTemplate, eventId, sourceTopic, payloadSha256, position, existing.get());
      return false;
    }
    try {
      jdbcTemplate.update(
          "INSERT INTO query_service.projection_inbox "
              + "(event_id, source_topic, payload_sha256, partition_id, offset_value, "
              + "received_at_unix_ms) VALUES (?, ?, ?, ?, ?, ?)",
          eventId,
          sourceTopic,
          payloadSha256,
          position.partition(),
          position.offset(),
          position.observedAtUnixMs());
      return true;
    } catch (DuplicateKeyException duplicate) {
      final Inbox.Entry inserted =
          Inbox.find(jdbcTemplate, eventId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "query projection inbox position conflicts", duplicate));
      Inbox.acceptEquivalentDuplicate(
          jdbcTemplate, eventId, sourceTopic, payloadSha256, position, inserted);
      return false;
    }
  }

  static void assertContiguous(
      JdbcOperations jdbcTemplate, String sourceTopic, QueryProjectionPosition position) {
    final Optional<Checkpoint> checkpoint =
        findCheckpoint(jdbcTemplate, sourceTopic, position.partition());
    if (checkpoint.isPresent()) {
      requireNextReadyPosition(sourceTopic, position, checkpoint.get());
    }
  }

  static void advance(
      JdbcOperations jdbcTemplate, String sourceTopic, QueryProjectionPosition position) {
    upsertCheckpoint(jdbcTemplate, sourceTopic, position, READY);
  }

  static void markRecoveryRequired(
      JdbcOperations jdbcTemplate, String sourceTopic, QueryProjectionPosition position) {
    final Long lastOffset =
        jdbcTemplate
            .query(
                "SELECT last_processed_offset "
                    + "FROM query_service.projection_checkpoint "
                    + "WHERE source_topic = ? AND partition_id = ?",
                (resultSet, ignored) -> resultSet.getLong(1),
                sourceTopic,
                position.partition())
            .stream()
            .findFirst()
            .orElse(Math.max(0L, position.offset() - 1L));
    upsertCheckpoint(
        jdbcTemplate,
        sourceTopic,
        new QueryProjectionPosition(
            position.partition(), lastOffset, position.observedAtUnixMs()),
        GAP_DETECTED);
  }

  static void resetForReplay(JdbcOperations jdbcTemplate) {
    jdbcTemplate.update("DELETE FROM query_service.execution_read_model");
    jdbcTemplate.update("DELETE FROM query_service.order_read_model");
    jdbcTemplate.update("DELETE FROM query_service.account_summary_read_model");
    jdbcTemplate.update("DELETE FROM query_service.projection_inbox");
    jdbcTemplate.update("DELETE FROM query_service.projection_checkpoint");
    jdbcTemplate.update("DELETE FROM query_service.active_market_reference");
  }

  private static Optional<Checkpoint> findCheckpoint(
      JdbcOperations jdbcTemplate, String sourceTopic, int partition) {
    final List<Checkpoint> checkpoints =
        jdbcTemplate.query(
            "SELECT last_processed_offset, recovery_state "
                + "FROM query_service.projection_checkpoint "
                + "WHERE source_topic = ? AND partition_id = ?",
            (resultSet, ignored) -> new Checkpoint(resultSet.getLong(1), resultSet.getString(2)),
            sourceTopic,
            partition);
    return checkpoints.stream().findFirst();
  }

  private static void requireNextReadyPosition(
      String sourceTopic, QueryProjectionPosition position, Checkpoint checkpoint) {
    if (!READY.equals(checkpoint.state()) || position.offset() != checkpoint.offset() + 1L) {
      throw gap(sourceTopic, position);
    }
  }

  private static QueryProjectionGapException gap(
      String sourceTopic, QueryProjectionPosition position) {
    return new QueryProjectionGapException(
        "query projection requires replay for "
            + sourceTopic
            + "-"
            + position.partition()
            + "-"
            + position.offset());
  }

  private static void upsertCheckpoint(
      JdbcOperations jdbcTemplate,
      String sourceTopic,
      QueryProjectionPosition position,
      String state) {
    if (!READY.equals(state) && !GAP_DETECTED.equals(state)) {
      throw new IllegalArgumentException("unsupported query projection recovery state");
    }
    if (isPostgres(jdbcTemplate)) {
      jdbcTemplate.update(
          "INSERT INTO query_service.projection_checkpoint "
              + "(source_topic, partition_id, last_processed_offset, recovery_state, "
              + "updated_at_unix_ms) VALUES (?, ?, ?, ?, ?) "
              + "ON CONFLICT (source_topic, partition_id) DO UPDATE SET "
              + "last_processed_offset = EXCLUDED.last_processed_offset, "
              + "recovery_state = EXCLUDED.recovery_state, "
              + "updated_at_unix_ms = EXCLUDED.updated_at_unix_ms",
          sourceTopic,
          position.partition(),
          position.offset(),
          state,
          position.observedAtUnixMs());
    } else {
      final int updated =
          jdbcTemplate.update(
              "UPDATE query_service.projection_checkpoint "
                  + "SET last_processed_offset = ?, recovery_state = ?, "
                  + "updated_at_unix_ms = ? "
                  + "WHERE source_topic = ? AND partition_id = ?",
              position.offset(),
              state,
              position.observedAtUnixMs(),
              sourceTopic,
              position.partition());
      if (updated == 0) {
        jdbcTemplate.update(
            "INSERT INTO query_service.projection_checkpoint "
                + "(source_topic, partition_id, last_processed_offset, recovery_state, "
                + "updated_at_unix_ms) VALUES (?, ?, ?, ?, ?)",
            sourceTopic,
            position.partition(),
            position.offset(),
            state,
            position.observedAtUnixMs());
      }
    }
  }

  private static boolean isPostgres(JdbcOperations jdbcTemplate) {
    return Boolean.TRUE.equals(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    connection
                        .getMetaData()
                        .getDatabaseProductName()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("postgresql")));
  }

  private static final class Inbox {
    private Inbox() {}

    private static Optional<Entry> find(JdbcOperations jdbcTemplate, String eventId) {
      return jdbcTemplate
          .query(
              "SELECT payload_sha256, source_topic, partition_id, offset_value "
                  + "FROM query_service.projection_inbox WHERE event_id = ?",
              (resultSet, ignored) ->
                  new Entry(
                      resultSet.getBytes(1),
                      resultSet.getString(2),
                      resultSet.getInt(3),
                      resultSet.getLong(4)),
              eventId)
          .stream()
          .findFirst();
    }

    private static void acceptEquivalentDuplicate(
        JdbcOperations jdbcTemplate,
        String eventId,
        String sourceTopic,
        byte[] payloadSha256,
        QueryProjectionPosition observed,
        Entry original) {
      original.requireCompatible(eventId, sourceTopic, payloadSha256, observed);
      consumePosition(jdbcTemplate, sourceTopic, observed);
    }

    private static void consumePosition(
        JdbcOperations jdbcTemplate, String sourceTopic, QueryProjectionPosition position) {
      final Checkpoint checkpoint =
          findCheckpoint(jdbcTemplate, sourceTopic, position.partition())
              .orElseThrow(() -> gap(sourceTopic, position));
      if (!READY.equals(checkpoint.state())) {
        throw gap(sourceTopic, position);
      }
      if (position.offset() <= checkpoint.offset()) {
        return;
      }
      if (position.offset() == checkpoint.offset() + 1L) {
        advance(jdbcTemplate, sourceTopic, position);
        return;
      }
      throw gap(sourceTopic, position);
    }

    private static final class Entry {
      private final byte[] payloadSha256;
      private final String sourceTopic;
      private final int partition;
      private final long offset;

      private Entry(byte[] payloadSha256, String sourceTopic, int partition, long offset) {
        this.payloadSha256 = payloadSha256.clone();
        this.sourceTopic = sourceTopic;
        this.partition = partition;
        this.offset = offset;
      }

      private void requireCompatible(
          String eventId,
          String observedTopic,
          byte[] observedPayloadSha256,
          QueryProjectionPosition observedPosition) {
        if (!Arrays.equals(payloadSha256, observedPayloadSha256)) {
          throw new IllegalStateException(
              "query projection received conflicting payload for event " + eventId);
        }
        if (!sourceTopic.equals(observedTopic)) {
          throw new IllegalStateException(
              "query projection duplicate event changed source topic for " + eventId);
        }
        if (partition != observedPosition.partition()) {
          throw new IllegalStateException(
              "query projection duplicate event changed Kafka partition for " + eventId);
        }
        if (observedPosition.offset() < offset) {
          throw new IllegalStateException(
              "query projection duplicate event moved before original Kafka offset for " + eventId);
        }
      }
    }
  }

  private record Checkpoint(long offset, String state) {}
}
