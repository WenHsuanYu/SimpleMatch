package com.simplematch.queryservice.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.RedisTemplate;

/** Redis adapter for short-lived query responses with PostgreSQL read-through fallback. */
public final class RedisQueryReadCache implements QueryReadCache {
  private static final Duration ENTRY_TTL = Duration.ofSeconds(30);
  private final RedisTemplate<String, byte[]> redisTemplate;
  private final ObjectMapper objectMapper;
  private final String keyPrefix;

  /** Creates the cache adapter over byte-preserving Redis values. */
  public RedisQueryReadCache(
      RedisTemplate<String, byte[]> redisTemplate, ObjectMapper objectMapper, String keyPrefix) {
    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
  }

  @Override
  public Optional<JsonNode> get(String key) {
    final byte[] value = redisTemplate.opsForValue().get(key);
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readTree(value));
    } catch (java.io.IOException invalid) {
      redisTemplate.delete(key);
      return Optional.empty();
    }
  }

  @Override
  public void put(String key, Object value) {
    try {
      redisTemplate.opsForValue().set(key, objectMapper.writeValueAsBytes(value), ENTRY_TTL);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("query response could not be cached", invalid);
    }
  }

  @Override
  public void clear() {
    final java.util.Set<String> keys = redisTemplate.keys(keyPrefix + ":*");
    if (keys != null && !keys.isEmpty()) {
      redisTemplate.delete(keys);
    }
  }
}
