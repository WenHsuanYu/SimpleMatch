package com.simplematch.marketdatapublisher.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the local transaction that activates one immutable daily market snapshot and its outbox
 * event.
 */
@RequiredArgsConstructor
public class MarketSnapshotApplicationService {
  static final String SNAPSHOT_PUBLISHED_TOPIC = "market-reference.snapshots";

  static final String SNAPSHOT_PUBLISHED_PAYLOAD_TYPE = "market_snapshot_published.v1";

  private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

  @NonNull
  private final MarketSnapshotRepository snapshots;

  @NonNull
  private final SnapshotOutbox outbox;

  @NonNull
  private final ObjectMapper objectMapper;

  @NonNull
  private final Clock clock;

  @NonNull
  private final Supplier<UUID> snapshotIds;

  @NonNull
  private final Supplier<UUID> eventIds;

  /**
   * Publishes prepared content as the current immutable version for its trading day.
   *
   * <p>All current-state checks, version allocation, activation, metadata, and outbox insertion run
   * in this one local transaction. Parsing and static validation happen before this method is
   * invoked.
   *
   * @param prepared immutable source content validated before the transaction begins
   * @return the existing duplicate result or the newly committed snapshot publication
   * @throws SnapshotPublicationFailure if publication cannot create its durable outcome
   */
  @Transactional(
      timeout = TRANSACTION_TIMEOUT_SECONDS,
      rollbackFor = SnapshotPublicationFailure.class)
  public SnapshotPublicationResult publishSnapshot(PreparedMarketSnapshot prepared)
      throws SnapshotPublicationFailure {
    Objects.requireNonNull(prepared, "prepared snapshot");
    final var duplicate =
        snapshots.findBySourceIdentityAndChecksum(prepared.sourceIdentity(), prepared.checksum());
    if (duplicate.isPresent()) {
      return result(duplicate.orElseThrow(), true);
    }
    snapshots.findActiveForUpdate(prepared.tradingDay());
    final long version = snapshots.nextVersion(prepared.tradingDay());
    snapshots.deactivateActive(prepared.tradingDay());
    final PublishedMarketSnapshot snapshot =
        PublishedMarketSnapshot.active(snapshotIds.get(), version, prepared, clock.instant());
    try {
      snapshots.insert(snapshot);
    } catch (DuplicateKeyException exception) {
      throw new SnapshotPublicationConflictException();
    }
    outbox.insert(outboxRecord(snapshot));
    return result(snapshot, false);
  }

  private SnapshotPublicationResult result(PublishedMarketSnapshot snapshot, boolean duplicate) {
    final SnapshotIdentity identity = snapshot.identity();
    return new SnapshotPublicationResult(
        identity.snapshotId(), identity.tradingDay(), identity.version(), duplicate);
  }

  private SnapshotOutboxRecord outboxRecord(PublishedMarketSnapshot snapshot)
      throws SnapshotPublicationFailure {
    final UUID eventId = eventIds.get();
    final SnapshotIdentity identity = snapshot.identity();
    final SnapshotPublicationState publication = snapshot.publication();
    final String payload = serialize(eventId, snapshot);
    return new SnapshotOutboxRecord(
        new SnapshotOutboxRecord.EventIdentity(eventId),
        new SnapshotOutboxRecord.Destination(
            SNAPSHOT_PUBLISHED_TOPIC, identity.tradingDay().toString()),
        new SnapshotOutboxRecord.Payload(
            payload.getBytes(StandardCharsets.UTF_8),
            SNAPSHOT_PUBLISHED_PAYLOAD_TYPE,
            serializeHeaders(eventId)),
        new SnapshotOutboxRecord.AggregateReference(
            "market_snapshot", identity.snapshotId().toString()),
        publication.publishedAt().toEpochMilli());
  }

  private String serialize(UUID eventId, PublishedMarketSnapshot snapshot)
      throws SnapshotPublicationFailure {
    final SnapshotIdentity identity = snapshot.identity();
    final SnapshotProvenance provenance = snapshot.provenance();
    final Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schema_version", "v1");
    envelope.put("event_id", eventId.toString());
    envelope.put("snapshot_id", identity.snapshotId().toString());
    envelope.put("trading_day", identity.tradingDay().toString());
    envelope.put("version", identity.version());
    envelope.put("checksum", provenance.checksum());
    envelope.put("source_timestamp_unix_ms", provenance.sourceTimestampUnixMs());
    return write(envelope);
  }

  private String serializeHeaders(UUID eventId) throws SnapshotPublicationFailure {
    return write(
        Map.of(
            "content_type",
            "application/json",
            "event_id",
            eventId.toString(),
            "payload_type",
            SNAPSHOT_PUBLISHED_PAYLOAD_TYPE));
  }

  private String write(Object value) throws SnapshotPublicationFailure {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new SnapshotPublicationFailure("failed to serialize snapshot publication event");
    }
  }
}
