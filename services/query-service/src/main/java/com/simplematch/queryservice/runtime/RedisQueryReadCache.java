package com.simplematch.queryservice.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

/** Redis adapter for short-lived query responses with PostgreSQL read-through fallback. */
public final class RedisQueryReadCache implements QueryReadCache {
  private static final Duration ENTRY_TTL = Duration.ofSeconds(30);
  private static final int SCAN_BATCH_SIZE = 100;
  private final RedisTemplate<String, byte[]> redisTemplate;
  private final ObjectMapper objectMapper;
  private final String keyPrefix;

  /** Creates the cache adapter over byte-preserving Redis values. */
  public RedisQueryReadCache(
      RedisTemplate<String, byte[]> redisTemplate, ObjectMapper objectMapper, String keyPrefix) {
    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9][A-Za-z0-9:_-]*")) {
      throw new IllegalArgumentException("query Redis key prefix must be a literal namespace");
    }
    this.keyPrefix = keyPrefix + ":";
  }

  @Override
  public Optional<JsonNode> get(String key) {
    requireOwnedKey(key);
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
    requireOwnedKey(key);
    try {
      redisTemplate.opsForValue().set(key, objectMapper.writeValueAsBytes(value), ENTRY_TTL);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("query response could not be cached", invalid);
    }
  }

  @Override
  public void clear() {
    final ScanOptions options =
        ScanOptions.scanOptions().match(keyPrefix + "*").count(SCAN_BATCH_SIZE).build();
    final List<String> batch = new ArrayList<>(SCAN_BATCH_SIZE);
    try (Cursor<String> keys = redisTemplate.scan(options)) {
      while (keys.hasNext()) {
        final String key = keys.next();
        requireOwnedKey(key);
        batch.add(key);
        if (batch.size() == SCAN_BATCH_SIZE) {
          redisTemplate.delete(batch);
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        redisTemplate.delete(batch);
      }
    }
  }

  private void requireOwnedKey(String key) {
    if (key == null || !key.startsWith(keyPrefix) || key.length() == keyPrefix.length()) {
      throw new IllegalArgumentException("query Redis key is outside its namespace");
    }
  }
}
