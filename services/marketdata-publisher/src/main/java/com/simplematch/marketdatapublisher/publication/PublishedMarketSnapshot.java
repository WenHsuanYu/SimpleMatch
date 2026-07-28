package com.simplematch.marketdatapublisher.publication;

import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable snapshot metadata and its immutable normalized content.
 */
public record PublishedMarketSnapshot(
        UUID snapshotId,
        LocalDate tradingDay,
        long version,
        String sourceIdentity,
        long sourceTimestampUnixMs,
        String checksum,
        String canonicalContent,
        boolean active,
        Instant publishedAt) {
    /**
     * Validates the database-facing snapshot state.
     */
    public PublishedMarketSnapshot {
        Objects.requireNonNull(snapshotId, "snapshot id is required");
        Objects.requireNonNull(tradingDay, "trading day is required");
        if (version <= 0 || sourceTimestampUnixMs <= 0) {
            throw new IllegalArgumentException("snapshot version and source timestamp must be positive");
        }
        sourceIdentity = requireText(sourceIdentity, "source identity");
        checksum = requireText(checksum, "checksum");
        canonicalContent = requireText(canonicalContent, "canonical content");
        Objects.requireNonNull(publishedAt, "published at is required");
    }

    /**
     * Creates active persisted state from prepared content and transaction-owned values.
     */
    public static PublishedMarketSnapshot active(
            UUID snapshotId, long version, PreparedMarketSnapshot prepared, Instant publishedAt) {
        return new PublishedMarketSnapshot(
                snapshotId,
                prepared.tradingDay(),
                version,
                prepared.sourceIdentity(),
                prepared.sourceTimestampUnixMs(),
                prepared.checksum(),
                prepared.canonicalContent(),
                true,
                publishedAt);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
