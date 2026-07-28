package com.simplematch.marketdatapublisher.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Deterministically normalized snapshot content ready for transactional publication.
 */
public record PreparedMarketSnapshot(
        String sourceIdentity,
        long sourceTimestampUnixMs,
        LocalDate tradingDay,
        String checksum,
        List<MarketInstrument> instruments,
        String canonicalContent) {
    /**
     * Validates immutable data prepared outside the database transaction.
     */
    public PreparedMarketSnapshot {
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new MarketSnapshotValidationException("source identity is required");
        }
        if (sourceTimestampUnixMs <= 0) {
            throw new MarketSnapshotValidationException("source timestamp must be positive");
        }
        Objects.requireNonNull(tradingDay, "trading day is required");
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new MarketSnapshotValidationException("checksum must be a lowercase SHA-256 hash");
        }
        instruments = List.copyOf(Objects.requireNonNull(instruments, "instruments are required"));
        if (instruments.isEmpty()) {
            throw new MarketSnapshotValidationException("snapshot must contain at least one instrument");
        }
        Objects.requireNonNull(canonicalContent, "canonical content is required");
    }

    /**
     * Calculates the lowercase SHA-256 checksum used to verify normalized snapshot content.
     *
     * @param canonicalContent normalized content whose bytes are hashed as UTF-8
     * @return the 64-character lowercase SHA-256 checksum
     * @throws NullPointerException if canonical content is absent
     */
    public static String checksumFor(String canonicalContent) {
        Objects.requireNonNull(canonicalContent, "canonical content is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalContent.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
