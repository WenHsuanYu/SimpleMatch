package com.simplematch.tools.recovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs only the supplied public command and retains its combined output. */
record CommandResult(int exitCode, String output) {
  static CommandResult run(Path output, List<String> arguments) throws Exception {
    return run(output, arguments, Map.of());
  }

  static CommandResult run(Path output, List<String> arguments, Map<String, String> environment)
      throws Exception {
    final ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true)
        .redirectOutput(output.toFile());
    builder.environment().putAll(environment);
    final Process process = builder.start();
    try {
      final boolean finished = process.waitFor(30, TimeUnit.SECONDS);
      assertTrue(finished, "Command timed out: " + Files.readString(output));
      return new CommandResult(process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  static CommandResult verifier(Path output, String... arguments) throws Exception {
    final List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
        System.getProperty("dependencyVerifierClasspath", System.getProperty("java.class.path")),
        "com.simplematch.tools.recovery.RecoveryEvidenceVerifierMain"));
    command.addAll(List.of(arguments));
    return run(output, command);
  }
}
