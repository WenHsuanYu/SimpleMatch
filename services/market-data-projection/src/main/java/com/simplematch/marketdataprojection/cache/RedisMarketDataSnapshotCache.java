package com.simplematch.marketdataprojection.cache;

import java.util.Objects;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis adapter for complete public snapshots; its data is always reconstructible from PostgreSQL.
 */
public final class RedisMarketDataSnapshotCache implements MarketDataSnapshotCache {
  private final RedisTemplate<String, byte[]> redisTemplate;

  /** Creates a Redis cache adapter that stores exact versioned Protobuf snapshot bytes. */
  public RedisMarketDataSnapshotCache(RedisTemplate<String, byte[]> redisTemplate) {
    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
  }

  @Override
  public void put(MarketDataSnapshotCacheEntry entry) {
    redisTemplate.opsForValue().set(entry.redisKey(), entry.payload());
  }
}
