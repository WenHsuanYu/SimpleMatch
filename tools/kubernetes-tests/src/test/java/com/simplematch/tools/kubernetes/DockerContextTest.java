package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerContextTest {
  @Test
  void bindsDockerAndKindToTheSameExplicitContext() {
    final var context = new DockerContext("desktop-linux");
    assertEquals(List.of("docker", "--context", "desktop-linux", "image", "inspect", "image:tag"),
        context.command("image", "inspect", "image:tag"));
    assertEquals("desktop-linux", context.environment().get("DOCKER_CONTEXT"));
  }

  @Test
  void rejectsMissingOrMalformedContextBeforeExternalOperations() {
    assertThrows(IllegalArgumentException.class, () -> new DockerContext(null));
    assertThrows(IllegalArgumentException.class, () -> new DockerContext(""));
    assertThrows(IllegalArgumentException.class, () -> new DockerContext("--context=default"));
  }
}
