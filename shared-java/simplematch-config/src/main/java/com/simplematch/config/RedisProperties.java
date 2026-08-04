package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independently bindable Redis endpoint capability for projections and caches. */
@ConfigurationProperties("simplematch.redis")
public record RedisProperties(String endpoints) {
  /** Normalizes absent Redis endpoints to the local Redis instance. */
  public RedisProperties {
    endpoints = PlatformPropertyDefaults.string(endpoints, "localhost:6379");
  }

  static RedisProperties defaults() {
    return new RedisProperties(null);
  }
}
