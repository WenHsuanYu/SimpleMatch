package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Exports one selected platform and binds its config bytes to an unchanged host reference. */
final class KubernetesImageArchive implements AutoCloseable {
  private final BoundedCommand commands;
  private final DockerContext context;
  private final Path archive;

  KubernetesImageArchive(BoundedCommand commands, DockerContext context, Path archive) {
    this.commands = commands;
    this.context = context;
    this.archive = java.util.Objects.requireNonNull(archive, "temporary image archive");
  }

  String save(String reference, String expectedId) throws Exception {
    requireCurrent(reference, expectedId);
    commands.run(context.command("image", "save", "--platform", "linux/amd64", "--output",
        archive.toString(), reference), "", Duration.ofMinutes(2)).checked();
    final String config = configIdentity(commands, archive);
    requireCurrent(reference, expectedId);
    return config;
  }

  /** Deletes the owned temporary file; try-with-resources preserves an earlier failure. */
  @Override
  public void close() throws IOException {
    Files.deleteIfExists(archive);
  }

  private void requireCurrent(String reference, String expectedId) throws Exception {
    final String observed = new ObjectMapper().readTree(commands.run(
        context.command("image", "inspect", reference), "", Duration.ofSeconds(30)).checked())
        .get(0).path("Id").asText();
    if (!expectedId.equals(observed)) {
      throw new IllegalStateException(
          "image reference changed during platform export: " + reference);
    }
  }

  /** Checks the archive's actual config digest and platform before any node import. */
  static String configIdentity(BoundedCommand commands, Path archive) throws Exception {
    final var json = new ObjectMapper();
    final var manifest = json.readTree(commands.run(List.of("tar", "-xOf", archive.toString(),
        "manifest.json"), "", Duration.ofSeconds(30)).checked());
    if (!manifest.isArray() || manifest.size() != 1) {
      throw new IllegalStateException("archive must contain exactly one platform image");
    }
    final String file = manifest.get(0).path("Config").asText();
    if (!file.matches("(?:blobs/sha256/)?[0-9a-f]{64}(?:\\.json)?")) {
      throw new IllegalStateException("archive has an invalid config identity");
    }
    final String config = commands.run(List.of("tar", "-xOf", archive.toString(), file), "",
        Duration.ofSeconds(30)).checked();
    final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(config.getBytes(StandardCharsets.UTF_8)));
    if (!file.contains(hash)) {
      throw new IllegalStateException("archive content differs from its config identity");
    }
    requireAmd64Config(json.readTree(config));
    return "sha256:" + hash;
  }

  private static void requireAmd64Config(JsonNode config) {
    if (!"amd64".equals(config.path("architecture").asText())
        || !"linux".equals(config.path("os").asText())) {
      throw new IllegalStateException("archive config is not linux/amd64");
    }
  }
}
