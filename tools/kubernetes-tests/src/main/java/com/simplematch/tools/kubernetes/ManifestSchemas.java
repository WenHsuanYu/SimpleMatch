package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Renders current overlays and rejects schema failures before deployment can start. */
final class ManifestSchemas {
  private final Path root;
  private final Path tools;
  private final String kubernetesVersion;

  private ManifestSchemas(Path root, Path tools, String kubernetesVersion) {
    this.root = root;
    this.tools = tools;
    this.kubernetesVersion = kubernetesVersion;
  }

  static ManifestSchemas prepare(Path root) throws Exception {
    final var inputs = new ObjectMapper().readTree(
        root.resolve("config/manifest-check.json").toFile());
    return new ManifestSchemas(root, PinnedManifestTools.prepare(root, inputs),
        inputs.path("kubernetesVersion").asText());
  }

  String render(String profile) throws Exception {
    if (!List.of("local", "test", "staging", "production").contains(profile)) {
      throw new IllegalArgumentException("unsupported manifest profile: " + profile);
    }
    return new BoundedCommand(root, Map.of()).run(List.of(tools.resolve("kustomize").toString(),
        "build", root.resolve("deploy/k8s/overlays/" + profile).toString(),
        "--load-restrictor", "LoadRestrictionsNone"), "", Duration.ofMinutes(1)).checked();
  }

  void validate(String rendered) throws Exception {
    final var result = new BoundedCommand(root, Map.of()).run(List.of(
        tools.resolve("kubeconform").toString(), "-strict", "-summary", "-output", "json",
        "-kubernetes-version", kubernetesVersion, "-schema-location",
        tools.resolve("schemas/{{.ResourceKind}}{{.KindSuffix}}.json").toString()),
        rendered, Duration.ofMinutes(1));
    if (result.truncated() || result.exitCode() != 0) {
      final String diagnostic = DiagnosticText.redact(result.output() + result.error());
      throw new IllegalArgumentException("manifest schema validation failed: "
          + diagnostic.substring(0, Math.min(8_000, diagnostic.length())));
    }
    final var summary = new ObjectMapper().readTree(result.output()).path("summary");
    if (summary.path("valid").asInt() < 1 || summary.path("invalid").asInt() != 0
        || summary.path("errors").asInt() != 0 || summary.path("skipped").asInt() != 0) {
      throw new IllegalArgumentException(
          "manifest validation requires resources and no errors or skips");
    }
    ManifestWorkloadPolicy.validate(rendered);
  }
}
