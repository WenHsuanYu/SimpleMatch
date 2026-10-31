package com.simplematch.tools.kubernetes;

import java.nio.file.Path;
import java.time.LocalDate;

/** Explicit inputs for one fresh RM-1 run; historical artifacts keep their original trading day. */
record KubernetesSettings(Path root, Path evidence, Path coordination, LocalDate day,
    Path delivery, String tag, KindMode mode, boolean retain, DockerContext dockerContext) {
  static Path coordinationDirectory() {
    return Path.of("/tmp/simplematch-kubernetes-coordination");
  }

  static KubernetesSettings selected() {
    final Path root = Path.of(System.getProperty("verificationRepoRoot")).toAbsolutePath();
    final String day = System.getProperty("kubernetesTradingDay", "2026-08-17");
    final String evidence = System.getProperty("kubernetesEvidence");
    if (evidence == null || evidence.isBlank()) {
      throw new IllegalArgumentException("kubernetesEvidence must name a new evidence directory");
    }
    final KindMode mode = KindMode.parse(System.getProperty("kubernetesMode", "dedicated"));
    final String tag = System.getProperty("kubernetesImageTag", "kubernetes");
    if (!tag.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,100}")) {
      throw new IllegalArgumentException("invalid Kubernetes image tag");
    }
    return new KubernetesSettings(root, root.resolve(evidence).normalize(),
        coordinationDirectory(),
        LocalDate.parse(day), root.resolve(System.getProperty("kubernetesDeliveryManifest",
            "tools/market-reference-builder/config/market-reference/approved/" + day
                + "/delivery/manifest.yaml")), tag, mode,
        Boolean.parseBoolean(System.getProperty("kubernetesRetain", "false")),
        new DockerContext(System.getProperty("kubernetesDockerContext")));
  }
}
