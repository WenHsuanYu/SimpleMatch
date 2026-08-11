package com.simplematch.marketdataprojection.cache;

import java.util.Arrays;
import java.util.Objects;

/**
 * One complete snapshot waiting to be materialized in Redis without becoming authoritative state.
 */
public final class MarketDataSnapshotCacheEntry {
  private final String venueMic;
  private final String symbol;
  private final byte[] eventId;
  private final byte[] payload;

  /** Defensively owns a valid Phase 1 Taiwan instrument cache payload. */
  public MarketDataSnapshotCacheEntry(
      String venueMic, String symbol, byte[] eventId, byte[] payload) {
    if (venueMic == null || venueMic.length() != 4 || symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("market-data cache instrument is invalid");
    }
    this.venueMic = venueMic;
    this.symbol = symbol;
    this.eventId = Objects.requireNonNull(eventId, "eventId").clone();
    this.payload = Objects.requireNonNull(payload, "payload").clone();
    if (this.eventId.length != 32) {
      throw new IllegalArgumentException("market-data cache event identity must contain 32 bytes");
    }
  }

  /** Returns the Market Identifier Code. */
  public String venueMic() {
    return venueMic;
  }

  /** Returns the Phase 1 instrument code. */
  public String symbol() {
    return symbol;
  }

  /** Returns the namespaced Redis key for one independently usable public snapshot. */
  public String redisKey() {
    return "marketdata:snapshot:" + venueMic + ":" + symbol;
  }

  /** Returns the stable event identity without exposing owned bytes. */
  public byte[] eventId() {
    return eventId.clone();
  }

  /** Returns the exact snapshot bytes without exposing owned bytes. */
  public byte[] payload() {
    return payload.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof MarketDataSnapshotCacheEntry candidate)) {
      return false;
    }
    return venueMic.equals(candidate.venueMic)
        && symbol.equals(candidate.symbol)
        && Arrays.equals(eventId, candidate.eventId)
        && Arrays.equals(payload, candidate.payload);
  }

  @Override
  public int hashCode() {
    int result = venueMic.hashCode();
    result = 31 * result + symbol.hashCode();
    result = 31 * result + Arrays.hashCode(eventId);
    return 31 * result + Arrays.hashCode(payload);
  }
}
