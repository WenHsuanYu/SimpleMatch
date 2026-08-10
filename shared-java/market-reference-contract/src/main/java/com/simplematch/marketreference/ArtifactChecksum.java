package com.simplematch.marketreference;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Calculates and validates the external SHA-256 checksum for artifact bytes. */
public final class ArtifactChecksum {
  private ArtifactChecksum() {}

  /**
   * Calculates a lowercase SHA-256 checksum for the exact supplied bytes.
   *
   * @param bytes canonical artifact bytes
   * @return a 64-character lowercase SHA-256 checksum
   */
  public static String sha256(byte[] bytes) {
    Objects.requireNonNull(bytes, "artifact bytes are required");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  /**
   * Validates that a supplied checksum has the canonical external form.
   *
   * @param checksum checksum to validate
   * @return the validated checksum
   */
  public static String requireCanonical(String checksum) {
    if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
      throw new MarketReferenceValidationException(
          "artifact checksum must be a 64-character lowercase SHA-256 value");
    }
    return checksum;
  }
}
