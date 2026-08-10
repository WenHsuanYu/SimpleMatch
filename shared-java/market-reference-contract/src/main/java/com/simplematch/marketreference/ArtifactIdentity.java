package com.simplematch.marketreference;

import java.time.LocalDate;
import java.util.Objects;

/** Identifies one immutable final artifact by trading day and exact content hash. */
public record ArtifactIdentity(LocalDate tradingDay, String contentSha256) {
  /** Validates the immutable identity components. */
  public ArtifactIdentity {
    Objects.requireNonNull(tradingDay, "trading day is required");
    contentSha256 = ArtifactChecksum.requireCanonical(contentSha256);
  }

  /**
   * Creates the identity corresponding to an externally supplied checksum.
   *
   * @param tradingDay Asia/Taipei trading day
   * @param contentSha256 SHA-256 of exact UTF-8 artifact bytes
   * @return immutable artifact identity
   */
  public static ArtifactIdentity of(LocalDate tradingDay, String contentSha256) {
    return new ArtifactIdentity(tradingDay, contentSha256);
  }

  /** Returns the stable text representation used in status and approval evidence. */
  public String value() {
    return tradingDay + ":" + contentSha256;
  }
}
