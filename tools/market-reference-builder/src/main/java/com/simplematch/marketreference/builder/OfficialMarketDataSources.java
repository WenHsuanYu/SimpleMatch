package com.simplematch.marketreference.builder;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete bounded set of official documents consumed by one offline build. */
public record OfficialMarketDataSources(
    Map<OfficialSourceType, RetrievedOfficialSource> documents) {
  /** Requires exactly one correctly labelled document for every required official source. */
  public OfficialMarketDataSources {
    Objects.requireNonNull(documents, "official source documents are required");
    final Map<OfficialSourceType, RetrievedOfficialSource> copied =
        new EnumMap<>(OfficialSourceType.class);
    copied.putAll(documents);
    for (OfficialSourceType sourceType : OfficialSourceType.values()) {
      final RetrievedOfficialSource source = copied.get(sourceType);
      if (source == null) {
        throw new MarketReferenceBuildException(
            "missing official source: " + sourceType.sourceId());
      }
      if (source.sourceType() != sourceType) {
        throw new MarketReferenceBuildException(
            "official source type does not match its document key");
      }
    }
    documents = Map.copyOf(copied);
  }

  /** Returns the source document for one required source type. */
  public RetrievedOfficialSource document(OfficialSourceType sourceType) {
    return documents.get(Objects.requireNonNull(sourceType, "source type is required"));
  }
}
