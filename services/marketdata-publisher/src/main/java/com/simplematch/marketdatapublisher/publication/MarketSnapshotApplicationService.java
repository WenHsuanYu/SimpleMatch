package com.simplematch.marketdatapublisher.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owns the local transaction that activates one immutable daily market snapshot and its outbox event.
 */
public class MarketSnapshotApplicationService {
    static final String SNAPSHOT_PUBLISHED_TOPIC = "market-reference.snapshots";
    static final String SNAPSHOT_PUBLISHED_PAYLOAD_TYPE = "market_snapshot_published.v1";
    private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

    private final MarketSnapshotRepository snapshots;
    private final SnapshotOutbox outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<UUID> snapshotIds;
    private final Supplier<UUID> eventIds;

    /**
     * Creates the application service with transaction-local persistence dependencies.
     */
    public MarketSnapshotApplicationService(
            MarketSnapshotRepository snapshots,
            SnapshotOutbox outbox,
            ObjectMapper objectMapper,
            Clock clock,
            Supplier<UUID> snapshotIds,
            Supplier<UUID> eventIds) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshotIds = Objects.requireNonNull(snapshotIds, "snapshotIds");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds");
    }

    /**
     * Publishes prepared content as the current immutable version for its trading day.
     *
     * <p>All current-state checks, version allocation, activation, metadata, and outbox insertion run
     * in this one local transaction. Parsing and static validation happen before this method is invoked.
     *
     * @param prepared immutable source content validated before the transaction begins
     * @return the existing duplicate result or the newly committed snapshot publication
     * @throws SnapshotPublicationFailure if publication cannot create its durable outcome
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS, rollbackFor = SnapshotPublicationFailure.class)
    public SnapshotPublicationResult publishSnapshot(PreparedMarketSnapshot prepared)
            throws SnapshotPublicationFailure {
        Objects.requireNonNull(prepared, "prepared snapshot");
        final var duplicate = snapshots.findBySourceIdentityAndChecksum(
                prepared.sourceIdentity(), prepared.checksum());
        if (duplicate.isPresent()) {
            return result(duplicate.orElseThrow(), true);
        }
        snapshots.findActiveForUpdate(prepared.tradingDay());
        final long version = snapshots.nextVersion(prepared.tradingDay());
        snapshots.deactivateActive(prepared.tradingDay());
        final PublishedMarketSnapshot snapshot = PublishedMarketSnapshot.active(
                snapshotIds.get(), version, prepared, clock.instant());
        try {
            snapshots.insert(snapshot);
        } catch (DuplicateKeyException exception) {
            throw new SnapshotPublicationConflictException();
        }
        outbox.insert(outboxRecord(snapshot));
        return result(snapshot, false);
    }

    private SnapshotPublicationResult result(PublishedMarketSnapshot snapshot, boolean duplicate) {
        return new SnapshotPublicationResult(snapshot.snapshotId(), snapshot.tradingDay(), snapshot.version(), duplicate);
    }

    private SnapshotOutboxRecord outboxRecord(PublishedMarketSnapshot snapshot)
            throws SnapshotPublicationFailure {
        final UUID eventId = eventIds.get();
        final String payload = serialize(eventId, snapshot);
        return SnapshotOutboxRecord.builder()
                .eventId(eventId)
                .topic(SNAPSHOT_PUBLISHED_TOPIC)
                .messageKey(snapshot.tradingDay().toString())
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .payloadType(SNAPSHOT_PUBLISHED_PAYLOAD_TYPE)
                .headersJson(serializeHeaders(eventId))
                .aggregateType("market_snapshot")
                .aggregateId(snapshot.snapshotId().toString())
                .createdAtUnixMs(snapshot.publishedAt().toEpochMilli())
                .build();
    }

    private String serialize(UUID eventId, PublishedMarketSnapshot snapshot) throws SnapshotPublicationFailure {
        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", "v1");
        envelope.put("event_id", eventId.toString());
        envelope.put("snapshot_id", snapshot.snapshotId().toString());
        envelope.put("trading_day", snapshot.tradingDay().toString());
        envelope.put("version", snapshot.version());
        envelope.put("checksum", snapshot.checksum());
        envelope.put("source_timestamp_unix_ms", snapshot.sourceTimestampUnixMs());
        return write(envelope);
    }

    private String serializeHeaders(UUID eventId) throws SnapshotPublicationFailure {
        return write(Map.of(
                "content_type", "application/json",
                "event_id", eventId.toString(),
                "payload_type", SNAPSHOT_PUBLISHED_PAYLOAD_TYPE));
    }

    private String write(Object value) throws SnapshotPublicationFailure {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SnapshotPublicationFailure("failed to serialize snapshot publication event");
        }
    }
}
