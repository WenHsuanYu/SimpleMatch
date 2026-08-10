package com.simplematch.marketreference.builder;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Retrieves the complete official document set outside every trading-path runtime. */
public final class OfficialMarketDataClient {
  private final OfficialSourceTransport transport;

  /** Creates a client using an explicit replaceable source transport. */
  public OfficialMarketDataClient(OfficialSourceTransport transport) {
    this.transport = Objects.requireNonNull(transport, "official source transport is required");
  }

  /** Retrieves all required official documents once for one offline build. */
  public OfficialMarketDataSources retrieveAll() {
    final Map<OfficialSourceType, RetrievedOfficialSource> sources =
        new EnumMap<>(OfficialSourceType.class);
    for (OfficialSourceType sourceType : OfficialSourceType.values()) {
      sources.put(sourceType, transport.retrieve(sourceType));
    }
    return new OfficialMarketDataSources(sources);
  }
}
