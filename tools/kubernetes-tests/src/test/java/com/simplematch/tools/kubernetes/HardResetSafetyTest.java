package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the real hard-reset entry points with an isolated, allowlisted fake CLI path. */
class HardResetSafetyTest {
  @TempDir Path temporary;

  @Test
  void rejectsUnownedMutationsAndRetainsExactCleanupOrder() throws Exception {
    final Path root = Path.of(System.getProperty("verificationRepoRoot"));
    final Path fixtures = root.resolve("scripts/testdata/hard-reset");
    final JsonNode baseline = new ObjectMapper().readTree(
        Files.readAllBytes(fixtures.resolve("baseline.json")));
    assertTrue(baseline.isArray());
    assertEquals(10, baseline.size());
    for (JsonNode scenario : baseline) {
      verifyScenario(root, fixtures, scenario);
    }
  }

  private void verifyScenario(Path root, Path fixtures, JsonNode scenario) throws Exception {
    final String name = scenario.required("scenario").asText();
    final Path sandbox = Files.createDirectory(temporary.resolve(name));
    final Path scripts = Files.createDirectory(sandbox.resolve("scripts"));
    final Path bin = Files.createDirectory(sandbox.resolve("bin"));
    Files.createDirectory(sandbox.resolve(".git"));
    copyTree(root.resolve("scripts/lib"), scripts.resolve("lib"));
    for (String file : List.of("hard-reset-local.sh", "manage-simplematch-live.sh",
        "manage-local-registry.sh", "build-local-images.sh", "simplematch-clean-local-disk.sh")) {
      Files.copy(root.resolve("scripts").resolve(file), scripts.resolve(file));
    }
    for (String command : List.of("bash", "dirname", "grep", "sleep")) {
      Files.createSymbolicLink(bin.resolve(command), findCommand(command));
    }
    for (String command : List.of("docker", "kind", "kubectl", "jq", "findmnt")) {
      if (command.equals("kind") && (name.equals("missing-kind")
          || name.equals("observation-failed"))) {
        continue;
      }
      final Path fake = bin.resolve(command);
      Files.copy(fixtures.resolve("command.sh"), fake);
      assertTrue(fake.toFile().setExecutable(true, true));
    }

    final Path state = sandbox.resolve("state");
    final Path calls = sandbox.resolve("calls");
    final Path output = sandbox.resolve("output.log");
    Files.writeString(state, name.equals("absent") ? "absent" : "");
    Files.writeString(calls, "");
    final var args = new ArrayList<String>();
    args.add(bin.resolve("bash").toString());
    args.add(scripts.resolve(scenario.path("runner")
        .asText("hard-reset-local.sh")).toString());
    if (scenario.has("arguments")) {
      scenario.required("arguments").forEach(value -> args.add(value.asText()));
    } else {
      args.addAll(List.of("--yes", "--keep-project-build-state", "--keep-registry-cache"));
    }
    if (name.equals("dry-run")) {
      args.add("--dry-run");
    }
    final ProcessBuilder builder = new ProcessBuilder(args);
    builder.environment().clear();
    builder.environment().put("PATH", bin.toString());
    builder.environment().put("HOME", sandbox.toString());
    builder.environment().put("HARD_RESET_SCENARIO", name);
    builder.environment().put("HARD_RESET_STATE", state.toString());
    builder.environment().put("HARD_RESET_CALLS", calls.toString());
    builder.redirectErrorStream(true);
    builder.redirectOutput(output.toFile());
    final Process process = builder.start();
    try {
      final boolean finished = process.waitFor(20, TimeUnit.SECONDS);
      assertTrue(finished, name + ": hard reset did not terminate");
      final String messages = Files.readString(output);
      assertEquals(scenario.required("status").asInt(), process.exitValue(),
          name + ": " + messages);
      assertTrue(messages.contains(scenario.required("message").asText()),
          name + ": expected rejection/completion message was absent: " + messages);
      final var expected = new ArrayList<String>();
      scenario.required("mutations").forEach(item -> expected.add(item.asText()));
      assertEquals(expected, Files.readAllLines(calls),
          name + ": a command changed an unowned resource or broke cleanup order");
    } finally {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static void copyTree(Path source, Path destination) throws Exception {
    try (var tree = Files.walk(source)) {
      for (Path path : tree.toList()) {
        final Path target = destination.resolve(source.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static Path findCommand(String name) {
    for (String entry : com.google.common.base.Splitter.on(':').split(System.getenv("PATH"))) {
      final Path candidate = Path.of(entry).resolve(name);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate.toAbsolutePath();
      }
    }
    throw new IllegalStateException("Missing isolated-test prerequisite: " + name);
  }
}
