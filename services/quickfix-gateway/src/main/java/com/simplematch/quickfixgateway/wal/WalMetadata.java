package com.simplematch.quickfixgateway.wal;

/** Durable identity and provenance for one gateway-local WAL record. */
public record WalMetadata(
    String schemaVersion, String recordId, long createdAtUnixMs, String sourceService) {
  /** Current stable command-WAL schema. Recovery state is versioned in its sidecar journal. */
  public static final String CURRENT_SCHEMA_VERSION = "v1";

  /** Requires the stable schema version and complete durable provenance. */
  public WalMetadata {
    if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("unsupported WAL schema version: " + schemaVersion);
    }
    recordId = WalValidation.requiredText(recordId, "record_id");
    if (createdAtUnixMs < 0) {
      throw new IllegalArgumentException("created_at_unix_ms must not be negative");
    }
    sourceService = WalValidation.requiredText(sourceService, "source_service");
  }
}
