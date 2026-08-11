package com.simplematch.queryservice.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Optional Redis read-through cache; PostgreSQL remains the durable fallback. */
public interface QueryReadCache {
  /** Returns a cached response when the key is present and decodable. */
  Optional<JsonNode> get(String key);

  /** Stores a versioned response after its PostgreSQL read succeeded. */
  void put(String key, Object value);

  /** Removes cached projections during a rebuild. */
  void clear();
}
