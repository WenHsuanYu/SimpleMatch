package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OwnedKindClusterTest {
  private static final Path MARKER = Path.of("/tmp/coordination/run-uuid/owner");

  @Test
  void acceptsTheExactReadOnlyDesktopBindAndItsObservedHostTranslation() {
    assertTrue(owned(MARKER.toString(), false, "desktop-linux"));
    assertTrue(owned("/host_mnt" + MARKER, false, "desktop-linux"));
  }

  @Test
  void rejectsWritableForeignAndWrongDaemonBinds() {
    assertFalse(owned(MARKER.toString(), true, "desktop-linux"));
    assertFalse(owned("/host_mnt/tmp/coordination/other-run/owner", false, "desktop-linux"));
    assertFalse(owned("/host_mnt" + MARKER, false, "default"));
  }

  private boolean owned(String source, boolean writable, String context) {
    final var node = new ObjectMapper().valueToTree(Map.of("Mounts", List.of(Map.of(
        "Type", "bind", "Source", source, "Destination", "/etc/simplematch-verification-owner",
        "RW", writable))));
    return OwnedKindCluster.hasOwnerMount(node, MARKER, new DockerContext(context));
  }
}
