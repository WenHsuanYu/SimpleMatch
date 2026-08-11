package com.simplematch.queryservice.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Disabled-cache implementation used by local and test deployments. */
public final class NoopQueryReadCache implements QueryReadCache {
  @Override
  public Optional<JsonNode> get(String key) {
    return Optional.empty();
  }

  @Override
  public void put(String key, Object value) {}

  @Override
  public void clear() {}
}
