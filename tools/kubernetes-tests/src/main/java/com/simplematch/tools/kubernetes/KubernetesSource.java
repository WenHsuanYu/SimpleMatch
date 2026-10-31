package com.simplematch.tools.kubernetes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Fingerprints actual runtime build inputs, including local uncommitted source. */
final class KubernetesSource {
  private KubernetesSource() {}

  static String fingerprint(Path root, BoundedCommand commands) throws Exception {
    return fingerprintSelected(root, commands, file -> runtimeInput(file)
        && !file.endsWith(".md") && !file.contains("/src/test/"));
  }

  static String verificationFingerprint(Path root, BoundedCommand commands) throws Exception {
    return fingerprintSelected(root, commands, file -> !file.endsWith(".md")
        && (runtimeInput(file) || file.startsWith("tools/kubernetes-tests/")
            || file.startsWith("deploy/k8s/") || file.startsWith("deploy/kind/")
            || file.equals("scripts/local-resource-budget.rb")
            || file.equals("scripts/lib/local-docker-storage.sh")
            || file.equals("scripts/lib/local-kind.sh")));
  }

  private static String fingerprintSelected(Path root, BoundedCommand commands,
      java.util.function.Predicate<String> selected) throws Exception {
    final String files = commands.run(List.of("git", "ls-files", "-co", "--exclude-standard",
        "-z"), "", Duration.ofSeconds(30)).checked();
    final var digest = MessageDigest.getInstance("SHA-256");
    for (String file : Arrays.stream(files.split("\u0000", -1)).distinct().sorted().toList()) {
      if (selected.test(file)) {
        final Path path = root.resolve(file);
        digest.update(file.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        if (Files.isRegularFile(path)) {
          digest.update(Files.readAllBytes(path));
        }
        digest.update((byte) 0);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static boolean runtimeInput(String file) {
    return file.startsWith("services/account-service/") || file.startsWith("services/risk-service/")
        || file.startsWith("shared-java/") || file.startsWith("proto/")
        || file.startsWith("tools/risk-matching-e2e-verifier/")
        || file.startsWith("build-logic/") || file.startsWith("gradle/")
        || file.startsWith("matching-engine/") || file.startsWith("deploy/docker/")
        || file.startsWith("config/") || file.startsWith("triplets/")
        || file.endsWith("/build.gradle.kts") || file.endsWith("/gradle.lockfile")
        || java.util.Set.of("gradlew", "gradle.lockfile", "CMakeLists.txt",
            "vcpkg.json", "vcpkg-configuration.json",
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", ".dockerignore")
            .contains(file);
  }
}
