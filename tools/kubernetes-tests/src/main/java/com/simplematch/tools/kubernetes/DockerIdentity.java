package com.simplematch.tools.kubernetes;

import java.time.Duration;

/** Preserves the selected context and the observed daemon identity as one cleanup target. */
record DockerIdentity(DockerContext context, String daemonId) {
  DockerIdentity {
    java.util.Objects.requireNonNull(context, "Docker context");
    if (daemonId == null || daemonId.isBlank()) {
      throw new IllegalArgumentException("Docker daemon identity is required");
    }
  }

  void requireCurrent(BoundedCommand commands) throws Exception {
    final String observed = commands.run(context.command("info", "--format", "{{.ID}}"), "",
        Duration.ofSeconds(30)).checked().trim();
    if (!daemonId.equals(observed)) {
      throw new IllegalStateException("Docker daemon changed; refusing deployment or cleanup");
    }
  }
}
