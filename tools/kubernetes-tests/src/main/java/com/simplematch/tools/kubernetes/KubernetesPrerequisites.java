package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.List;

/** Checks host capacity and canonical inputs before creating deployment resources. */
record KubernetesPrerequisites(DockerIdentity docker, KubernetesInput input,
    RiskCommandProfile profile, JsonNode budget, String sourceFingerprint,
    String verificationFingerprint) {
  static KubernetesPrerequisites observe(KubernetesSettings settings) throws Exception {
    if (Files.exists(settings.evidence(), LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("evidence already exists; select a fresh destination");
    }
    final var commands = settings.dockerContext().commands(settings.root());
    final var json = new ObjectMapper();
    final var info = json.readTree(commands.run(
        settings.dockerContext().command("info", "--format", "{{json .}}"), "",
        Duration.ofSeconds(30)).checked());
    final var input = KubernetesInput.load(settings.delivery(), settings.day());
    final var profile = RiskCommandProfile.render(settings.root(), commands);
    final var yaml = new ObjectMapper(new YAMLFactory());
    final var manifest = new StringBuilder();
    for (var document : profile.documents()) {
      manifest.append(yaml.writeValueAsString(document));
    }
    final JsonNode budget = json.readTree(commands.run(List.of("ruby",
        "scripts/local-resource-budget.rb", "--manifest", "-", "--profile", "risk-command",
        "--host-memory-bytes", info.path("MemTotal").asText(), "--check"),
        manifest.toString(), Duration.ofSeconds(30)).checked());
    requireTools(commands);
    requireSharedDirectory(settings);
    KindTopology.preflight(settings, commands);
    commands.run(List.of("bash", "scripts/lib/local-docker-storage.sh"), "",
        Duration.ofSeconds(30)).checked();
    if (!"desktop-linux".equals(settings.dockerContext().name())) {
      checkFilesystem(commands, info);
    }
    new KubernetesCapacity(commands, settings.dockerContext())
        .require(info, budget, profile.platformImages());
    return new KubernetesPrerequisites(
        new DockerIdentity(settings.dockerContext(), info.path("ID").asText()), input, profile,
        budget, KubernetesSource.fingerprint(settings.root(), commands),
        KubernetesSource.verificationFingerprint(settings.root(), commands));
  }

  String daemonId() {
    return docker.daemonId();
  }

  private static void checkFilesystem(BoundedCommand commands, JsonNode info) throws Exception {
    final String filesystem = commands.run(List.of("findmnt", "-T",
        info.path("DockerRootDir").asText(), "-no", "FSTYPE"), "", Duration.ofSeconds(10))
        .checked().trim();
    if (!java.util.Set.of("ext4", "xfs", "btrfs", "zfs").contains(filesystem)) {
      throw new IllegalStateException("Docker requires a supported Linux filesystem");
    }
  }

  private static void requireTools(BoundedCommand commands) throws Exception {
    for (var command : List.of(List.of("kind", "version"), List.of("tar", "--version"),
        List.of("kubectl", "version", "--client", "-o", "json"), List.of("jq", "--version"),
        List.of("timeout", "--version"))) {
      commands.run(command, "", Duration.ofSeconds(10)).checked();
    }
  }

  private static void requireSharedDirectory(KubernetesSettings settings) throws Exception {
    if (!"desktop-linux".equals(settings.dockerContext().name())) {
      return;
    }
    final var file = java.nio.file.Path.of(System.getProperty("user.home"), ".docker", "desktop",
        "settings-store.json");
    final var directories = new ObjectMapper().readTree(file.toFile())
        .path("FilesharingDirectories");
    final boolean shared = java.util.stream.StreamSupport.stream(directories.spliterator(), false)
        .map(value -> java.nio.file.Path.of(value.asText()).toAbsolutePath().normalize())
        .anyMatch(settings.coordination()::startsWith);
    if (!shared) {
      throw new IllegalStateException("Docker Desktop must share " + settings.coordination()
          + "; add that directory in Resources > File sharing and apply before running S3");
    }
  }

}
