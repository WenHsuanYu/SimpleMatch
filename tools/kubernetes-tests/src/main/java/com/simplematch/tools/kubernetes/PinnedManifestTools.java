package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Acquires content-pinned build inputs without installing host tools or consulting a cluster. */
final class PinnedManifestTools {
  private PinnedManifestTools() {}

  static Path prepare(Path root, JsonNode inputs) throws Exception {
    final Path directory = root.resolve("tools/kubernetes-tests/build/manifest-inputs")
        .resolve(sha256(inputs.toString().getBytes(StandardCharsets.UTF_8)));
    Files.createDirectories(directory.resolve("schemas"));
    final String platform = platform();
    final var commands = new BoundedCommand(root, Map.of());
    for (String tool : List.of("kubeconform", "kustomize")) {
      final JsonNode artifact = inputs.path("tools").path(tool).path(platform);
      final Path archive = directory.resolve(tool + "-" + platform + ".tar.gz");
      acquire(URI.create(artifact.path("url").asText()),
          artifact.path("sha256").asText(), archive);
      // Extract again from the verified archive so an altered cached executable cannot be trusted.
      commands.run(List.of("tar", "-xzf", archive.toString(), "-C", directory.toString(), tool),
          "", Duration.ofSeconds(30)).checked();
      if (!directory.resolve(tool).toFile().setExecutable(true, true)) {
        throw new IOException("cannot make the pinned " + tool + " executable");
      }
    }
    final String source = "https://raw.githubusercontent.com/yannh/kubernetes-json-schema/"
        + inputs.path("schemaRevision").asText() + "/v"
        + inputs.path("kubernetesVersion").asText() + "-standalone-strict/";
    final var schemas = inputs.path("schemas").properties().iterator();
    while (schemas.hasNext()) {
      final var entry = schemas.next();
      acquire(URI.create(source + entry.getKey()), entry.getValue().asText(),
          directory.resolve("schemas").resolve(entry.getKey()));
    }
    if (!inputs.path("customResourceExceptions").isArray()
        || !inputs.path("customResourceExceptions").isEmpty()) {
      throw new IllegalArgumentException(
          "custom-resource exceptions require a reviewed validation owner");
    }
    return directory;
  }

  private static String platform() {
    final String os = Map.of("Linux", "linux", "Mac OS X", "darwin")
        .get(System.getProperty("os.name"));
    final String architecture = Map.of("amd64", "amd64", "x86_64", "amd64",
        "aarch64", "arm64", "arm64", "arm64").get(System.getProperty("os.arch"));
    if (os == null || architecture == null) {
      throw new IllegalArgumentException(
          "manifest checks require Linux or macOS on amd64 or arm64");
    }
    return os + "-" + architecture;
  }

  private static void acquire(URI source, String expected, Path destination) throws Exception {
    if (!expected.matches("[0-9a-f]{64}") || !"https".equals(source.getScheme())) {
      throw new IllegalArgumentException("manifest inputs require HTTPS and a SHA-256 content pin");
    }
    if (!Files.exists(destination)) {
      final Path parent = Objects.requireNonNull(
          destination.getParent(), "manifest input directory");
      final Path temporary = Files.createTempFile(parent, "download-", ".tmp");
      try {
        new BoundedCommand(parent, Map.of()).run(List.of("curl", "--fail",
            "--silent", "--show-error", "--location", "--proto", "=https",
            "--connect-timeout", "20", "--max-time", "60", "--output", temporary.toString(),
            source.toString()), "", Duration.ofSeconds(65)).checked();
        verify(Files.readAllBytes(temporary), expected, destination);
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    verify(Files.readAllBytes(destination), expected, destination);
  }

  private static void verify(byte[] bytes, String expected, Path destination) throws Exception {
    final String actual = sha256(bytes);
    if (!expected.equals(actual)) {
      throw new IOException("pinned manifest input checksum mismatch: " + destination);
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
