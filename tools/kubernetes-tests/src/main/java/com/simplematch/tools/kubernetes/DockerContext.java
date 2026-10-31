package com.simplematch.tools.kubernetes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Binds Docker CLI operations and kind child processes to one explicit local context. */
record DockerContext(String name) {
  DockerContext {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("select a valid kubernetesDockerContext");
    }
  }

  List<String> command(String... arguments) {
    final var command = new ArrayList<>(List.of("docker", "--context", name));
    command.addAll(Arrays.asList(arguments));
    return List.copyOf(command);
  }

  Map<String, String> environment() {
    return Map.of("DOCKER_CONTEXT", name);
  }

  BoundedCommand commands(Path root) {
    return new BoundedCommand(root, environment());
  }
}
