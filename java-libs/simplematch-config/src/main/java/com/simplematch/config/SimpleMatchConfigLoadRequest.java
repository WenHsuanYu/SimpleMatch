package com.simplematch.config;

import java.nio.file.Path;
import java.util.Map;

public record SimpleMatchConfigLoadRequest(
    Path workingDirectory,
    Map<String, String> environment,
    SimpleMatchConfigOverrides overrides) {

  public SimpleMatchConfigLoadRequest {
    workingDirectory = workingDirectory.toAbsolutePath().normalize();
    environment = Map.copyOf(environment);
    overrides = overrides == null ? new SimpleMatchConfigOverrides(null, null, null, null) : overrides;
  }
}