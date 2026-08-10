package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactChecksum;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** Exact bytes and retrieval evidence for one official source document. */
public final class RetrievedOfficialSource {
  private final OfficialSourceType sourceType;
  private final URI sourceUrl;
  private final Instant retrievedAt;
  private final byte[] content;

  /** Defensively captures required source identity and immutable document bytes. */
  public RetrievedOfficialSource(
      OfficialSourceType sourceType, URI sourceUrl, Instant retrievedAt, byte[] content) {
    this.sourceType = Objects.requireNonNull(sourceType, "source type is required");
    this.sourceUrl = Objects.requireNonNull(sourceUrl, "source URL is required");
    this.retrievedAt = Objects.requireNonNull(retrievedAt, "source retrieval time is required");
    if (content == null || content.length == 0) {
      throw new MarketReferenceBuildException("official source content is required");
    }
    this.content = content.clone();
  }

  /** Returns the required official source classification. */
  public OfficialSourceType sourceType() {
    return sourceType;
  }

  /** Returns the exact source endpoint or file identity. */
  public URI sourceUrl() {
    return sourceUrl;
  }

  /** Returns when this exact document was acquired. */
  public Instant retrievedAt() {
    return retrievedAt;
  }

  /** Returns an isolated copy of the exact retrieved bytes. */
  public byte[] content() {
    return content.clone();
  }

  /** Returns the SHA-256 of the exact retrieved bytes. */
  public String contentSha256() {
    return ArtifactChecksum.sha256(content);
  }
}
