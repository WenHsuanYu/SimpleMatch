package com.simplematch.marketreference.builder;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Deterministic official-source fixture loader shared by builder unit tests. */
final class OfficialSourceFixtures {
  private OfficialSourceFixtures() {}

  static OfficialMarketDataSources load(Class<?> owner, Instant retrievedAt) throws IOException {
    final Map<OfficialSourceType, RetrievedOfficialSource> documents =
        new EnumMap<>(OfficialSourceType.class);
    for (OfficialSourceType sourceType : OfficialSourceType.values()) {
      documents.put(sourceType, source(owner, sourceType, retrievedAt));
    }
    return new OfficialMarketDataSources(documents);
  }

  private static RetrievedOfficialSource source(
      Class<?> owner, OfficialSourceType sourceType, Instant retrievedAt) throws IOException {
    final String resourceName = "/official-sources/" + sourceType.fixtureFileName();
    try (InputStream input = owner.getResourceAsStream(resourceName)) {
      if (input == null) {
        throw new IOException("missing fixture resource: " + resourceName);
      }
      return new RetrievedOfficialSource(
          sourceType, sourceType.endpoint(), retrievedAt, input.readAllBytes());
    }
  }
}
