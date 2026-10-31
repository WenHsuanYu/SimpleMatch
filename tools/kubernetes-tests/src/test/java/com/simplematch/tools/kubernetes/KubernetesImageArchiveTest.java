package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesImageArchiveTest {
  @TempDir Path temporary;

  @Test
  void bindsTheExportedAmd64ConfigToItsActualBytes() throws Exception {
    final var fixture = archive("amd64", false);
    assertEquals(fixture.identity(), KubernetesImageArchive.configIdentity(
        new BoundedCommand(temporary, Map.of()), fixture.archive()));
  }

  @Test
  void rejectsWrongPlatformsAndChangedConfigBytesBeforeImport() throws Exception {
    for (var fixture : List.of(archive("arm64", false), archive("amd64", true))) {
      assertThrows(IllegalStateException.class, () -> KubernetesImageArchive.configIdentity(
          new BoundedCommand(temporary, Map.of()), fixture.archive()));
    }
  }

  private Fixture archive(String architecture, boolean tampered) throws Exception {
    final var directory = Files.createTempDirectory(temporary, "config-");
    final String config = new ObjectMapper().writeValueAsString(
        Map.of("architecture", architecture, "os", "linux"));
    final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(config.getBytes(StandardCharsets.UTF_8)));
    final String file = "blobs/sha256/" + hash;
    Files.createDirectories(directory.resolve("blobs/sha256"));
    Files.writeString(directory.resolve(file), tampered ? config + " " : config);
    Files.writeString(directory.resolve("manifest.json"), new ObjectMapper().writeValueAsString(
        List.of(Map.of("Config", file, "RepoTags", List.of("fixture:tag"), "Layers", List.of()))));
    final var archive = directory.resolve("image.tar");
    new BoundedCommand(temporary, Map.of()).run(List.of("tar", "-cf", archive.toString(),
        "-C", directory.toString(), "manifest.json", "blobs"), "", Duration.ofSeconds(5)).checked();
    return new Fixture(archive, "sha256:" + hash);
  }

  private record Fixture(Path archive, String identity) {}
}
